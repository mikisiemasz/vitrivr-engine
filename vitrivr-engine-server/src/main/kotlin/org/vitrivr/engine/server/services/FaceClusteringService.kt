package org.vitrivr.engine.server.services

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.vitrivr.engine.core.model.descriptor.vector.FloatVectorDescriptor
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.core.model.relationship.Relationship
import org.vitrivr.engine.core.model.retrievable.Ingested
import org.vitrivr.engine.core.model.retrievable.RetrievableId
import org.vitrivr.engine.module.features.feature.external.ExternalAnalyser.Companion.HOST_PARAMETER_DEFAULT
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.*

private val logger: KLogger = KotlinLogging.logger {}

/**
 * Parameters for one face clustering run.
 *
 * @property embeddingFieldName  Schema field that holds the face embeddings (default: "face").
 * @property minClusterSize      HDBSCAN min_cluster_size.
 * @property minSamples          HDBSCAN min_samples.
 * @property pythonServerUrl     Base URL of the Python descriptor server.
 * @property exemplarCount       How many centroid-nearest detections to store as cluster exemplars.
 * @property labelCarryThreshold Cosine similarity above which a new cluster's centroid is considered
 *                               the same identity as a previously labelled cluster and inherits its label.
 */
data class ClusteringParams(
    val embeddingFieldName: String = "face",
    val minClusterSize: Int = 5,
    val minSamples: Int = 3,
    val pythonServerUrl: String = HOST_PARAMETER_DEFAULT,
    val exemplarCount: Int = 5,
    val labelCarryThreshold: Float = 0.6f,
)

/** Summary of a completed or in-progress clustering run. */
data class ClusterRunInfo(
    val runId: UUID,
    val algorithm: String,
    val embeddingField: String,
    val minClusterSize: Int,
    val minSamples: Int,
    val numInputFaces: Int,
    val numAssignedFaces: Int,
    val numNoiseFaces: Int,
    val numClusters: Int,
    val numLabelsCarried: Int,
    val startedAt: String,
    val completedAt: String,
    val status: String,
)

@Serializable
private data class ClusterRequest(
    val embeddings: List<List<Float>>,
    val detection_ids: List<String>,
    val min_cluster_size: Int,
    val min_samples: Int,
)

@Serializable
private data class ClusterResponse(
    val labels: List<Int>,
    val n_clusters: Int,
)

/**
 * Orchestrates one face clustering run:
 *
 * 1. Loads all FACE_DETECTION retrievables from the schema and their face embeddings.
 * 2. Sends the embedding corpus to the Python HDBSCAN endpoint.
 * 3. Creates FACE_CLUSTER_RUN + FACE_CLUSTER retrievables and `memberOfCluster` /
 *    `producedByRun` relationships.
 * 4. Computes each cluster's centroid and picks the K centroid-nearest detections as exemplars
 *    (persisted as `exemplarOfCluster` relationships).
 * 5. Carries labels forward from previously labelled clusters by nearest-centroid cosine match.
 *
 * Side-channel state (centroids + labels) is stored via [ClusterStateStore] because we don't
 * want centroids polluting the `face` embedding field's ANN index.
 */
class FaceClusteringService(
    private val schema: Schema,
    private val state: ClusterStateStore = ClusterStateStore.forSchema(schema.name),
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newHttpClient()

    fun run(params: ClusteringParams): ClusterRunInfo {
        val startedAt = Instant.now().toString()

        val reader = schema.connection.getRetrievableReader()
        val detections = reader.getAll("FACE_DETECTION").toList()
        logger.info { "[Clustering] Found ${detections.size} FACE_DETECTION retrievables." }
        if (detections.isEmpty()) return emptyRun(params, startedAt, "NO_DETECTIONS")

        @Suppress("UNCHECKED_CAST")
        val embField = schema[params.embeddingFieldName] as? Schema.Field<*, FloatVectorDescriptor>
            ?: run {
                logger.error { "[Clustering] Embedding field '${params.embeddingFieldName}' not found." }
                return emptyRun(params, startedAt, "FIELD_NOT_FOUND")
            }

        val detectionIds: List<RetrievableId> = detections.map { it.id }
        val embReader = embField.getReader()
        /* Postgres prepared statements cap at 65,535 params; chunk the IN(...) lookup. */
        val embeddingByDetection: Map<RetrievableId, FloatVectorDescriptor> =
            detectionIds.chunked(30_000)
                .flatMap { batch -> embReader.getAllForRetrievable(batch).toList() }
                .groupBy { it.retrievableId!! }
                .mapValues { (_, descs) -> descs.first() }

        val indexedDetectionIds = detectionIds.filter { it in embeddingByDetection }
        val embeddings = indexedDetectionIds.map { id ->
            embeddingByDetection[id]!!.vector.value.toList()
        }
        logger.info { "[Clustering] ${embeddings.size} detections have embeddings." }
        if (embeddings.isEmpty()) return emptyRun(params, startedAt, "NO_EMBEDDINGS")

        val clusterResp = callClusterEndpoint(params, indexedDetectionIds, embeddings)
            ?: return emptyRun(params, startedAt, "PYTHON_ERROR")

        val labels = clusterResp.labels
        val nClusters = clusterResp.n_clusters
        logger.info { "[Clustering] HDBSCAN returned $nClusters clusters." }

        val writer = schema.connection.getRetrievableWriter()
        val runId = UUID.randomUUID()
        val completedAt = Instant.now().toString()

        writer.add(Ingested(runId, "FACE_CLUSTER_RUN", transient = false))

        val clusterGroups: Map<Int, List<RetrievableId>> = labels
            .withIndex()
            .filter { (_, label) -> label >= 0 }
            .groupBy({ (_, label) -> label }, { (i, _) -> indexedDetectionIds[i] })

        /* Load labelled centroids from previous runs so we can carry forward labels. */
        val labelled = state.allLabels()
        val labelledCentroids: List<Pair<UUID, FloatArray>> = labelled.keys
            .mapNotNull { id -> state.getCentroid(id)?.let { id to it } }
        logger.info { "[Clustering] ${labelledCentroids.size} previously-labelled clusters available for carry-over." }

        val newCentroids = mutableMapOf<UUID, FloatArray>()
        var labelsCarried = 0

        for ((_, memberIds) in clusterGroups) {
            val clusterId = UUID.randomUUID()
            writer.add(Ingested(clusterId, "FACE_CLUSTER", transient = false))
            writer.connect(Relationship.ById(clusterId, "producedByRun", runId, transient = false))

            val memberships = memberIds.map { detId ->
                Relationship.ById(detId, "memberOfCluster", clusterId, transient = false)
            }
            writer.connectAll(memberships)

            /* Compute centroid + exemplars. */
            val memberVectors = memberIds.mapNotNull { embeddingByDetection[it]?.vector?.value }
            if (memberVectors.isEmpty()) continue
            val centroid = computeMeanNormalized(memberVectors)
            newCentroids[clusterId] = centroid

            val exemplars = topKNearestToCentroid(memberIds, memberVectors, centroid, params.exemplarCount)
            val exemplarRels = exemplars.map { detId ->
                Relationship.ById(detId, "exemplarOfCluster", clusterId, transient = false)
            }
            writer.connectAll(exemplarRels)

            /* Carry forward label from the nearest previously-labelled centroid, if close enough. */
            val best = labelledCentroids.maxByOrNull { cosine(centroid, it.second) }
            if (best != null && cosine(centroid, best.second) >= params.labelCarryThreshold) {
                val carried = labelled[best.first]
                if (!carried.isNullOrBlank()) {
                    state.setLabel(clusterId, carried)
                    labelsCarried++
                }
            }
        }

        state.setCentroids(newCentroids)

        val numAssigned = labels.count { it >= 0 }
        val numNoise = labels.count { it < 0 }

        logger.info {
            "[Clustering] Run $runId: $nClusters clusters, $numAssigned assigned, $numNoise noise, $labelsCarried labels carried."
        }

        return ClusterRunInfo(
            runId = runId,
            algorithm = "HDBSCAN",
            embeddingField = params.embeddingFieldName,
            minClusterSize = params.minClusterSize,
            minSamples = params.minSamples,
            numInputFaces = embeddings.size,
            numAssignedFaces = numAssigned,
            numNoiseFaces = numNoise,
            numClusters = nClusters,
            numLabelsCarried = labelsCarried,
            startedAt = startedAt,
            completedAt = completedAt,
            status = "COMPLETED",
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun callClusterEndpoint(
        params: ClusteringParams,
        detectionIds: List<RetrievableId>,
        embeddings: List<List<Float>>,
    ): ClusterResponse? {
        val url = "${params.pythonServerUrl.trimEnd('/')}/cluster/face_embeddings"
        val body = json.encodeToString(
            ClusterRequest(
                embeddings = embeddings,
                detection_ids = detectionIds.map { it.toString() },
                min_cluster_size = params.minClusterSize,
                min_samples = params.minSamples,
            )
        )
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (resp.statusCode() !in 200..299) {
                logger.error { "[Clustering] Python server HTTP ${resp.statusCode()} from $url" }
                return null
            }
            json.decodeFromStream<ClusterResponse>(resp.body())
        } catch (e: Exception) {
            logger.error(e) { "[Clustering] Failed to call $url" }
            null
        }
    }

    private fun emptyRun(params: ClusteringParams, startedAt: String, status: String): ClusterRunInfo =
        ClusterRunInfo(
            runId = UUID.randomUUID(),
            algorithm = "HDBSCAN",
            embeddingField = params.embeddingFieldName,
            minClusterSize = params.minClusterSize,
            minSamples = params.minSamples,
            numInputFaces = 0,
            numAssignedFaces = 0,
            numNoiseFaces = 0,
            numClusters = 0,
            numLabelsCarried = 0,
            startedAt = startedAt,
            completedAt = Instant.now().toString(),
            status = status,
        )

    companion object {
        /** Mean of input vectors, then L2-normalised. Inputs are assumed already L2-normalised. */
        fun computeMeanNormalized(vectors: List<FloatArray>): FloatArray {
            require(vectors.isNotEmpty())
            val dim = vectors[0].size
            val sum = FloatArray(dim)
            for (v in vectors) for (i in 0 until dim) sum[i] += v[i]
            for (i in 0 until dim) sum[i] /= vectors.size.toFloat()
            val norm = kotlin.math.sqrt(sum.fold(0f) { a, x -> a + x * x }).coerceAtLeast(1e-12f)
            for (i in 0 until dim) sum[i] /= norm
            return sum
        }

        /** Cosine similarity (assumes both already L2-normalised; falls back to safe form). */
        fun cosine(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size)
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot
        }

        /** Return the top-K ids whose embeddings are closest (cosine) to centroid. */
        fun topKNearestToCentroid(
            ids: List<RetrievableId>,
            vectors: List<FloatArray>,
            centroid: FloatArray,
            k: Int,
        ): List<RetrievableId> {
            require(ids.size == vectors.size)
            val scored = ids.indices.map { i -> ids[i] to cosine(vectors[i], centroid) }
            return scored.sortedByDescending { it.second }.take(k).map { it.first }
        }
    }
}
