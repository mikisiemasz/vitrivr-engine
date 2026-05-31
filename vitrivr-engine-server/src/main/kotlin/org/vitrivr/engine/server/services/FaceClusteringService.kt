package org.vitrivr.engine.server.services

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
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
 * @property embeddingFieldName Schema field that holds the face embeddings (default: "face").
 * @property minClusterSize     HDBSCAN min_cluster_size.
 * @property minSamples         HDBSCAN min_samples.
 * @property pythonServerUrl    Base URL of the Python descriptor server.
 */
data class ClusteringParams(
    val embeddingFieldName: String = "face",
    val minClusterSize: Int = 5,
    val minSamples: Int = 3,
    val pythonServerUrl: String = HOST_PARAMETER_DEFAULT,
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
 * 1. Loads all FACE_DETECTION retrievables from the schema.
 * 2. Loads their face-embedding descriptors.
 * 3. Sends the embedding corpus to the Python HDBSCAN endpoint.
 * 4. Creates [FACE_CLUSTER_RUN] and [FACE_CLUSTER] retrievables plus membership
 *    and provenance relationships in the database.
 *
 * Clustering is triggered manually (CLI `cluster` command or REST endpoint).
 *
 * @param schema The active [Schema] (used for DB access).
 */
class FaceClusteringService(private val schema: Schema) {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newHttpClient()

    /**
     * Runs one clustering pass and returns a [ClusterRunInfo] summarising the outcome.
     *
     * @param params Clustering parameters.
     * @return Summary of the completed run.
     */
    fun run(params: ClusteringParams): ClusterRunInfo {
        val startedAt = Instant.now().toString()

        // load all face detections
        val reader = schema.connection.getRetrievableReader()
        val detections = reader.getAll("FACE_DETECTION").toList()
        logger.info { "[Clustering] Found ${detections.size} FACE_DETECTION retrievables." }

        if (detections.isEmpty()) {
            return emptyRun(params, startedAt, "NO_DETECTIONS")
        }

        // load face embeddings
        @Suppress("UNCHECKED_CAST")
        val embField = schema[params.embeddingFieldName] as? Schema.Field<*, FloatVectorDescriptor>
            ?: run {
                logger.error { "[Clustering] Embedding field '${params.embeddingFieldName}' not found in schema." }
                return emptyRun(params, startedAt, "FIELD_NOT_FOUND")
            }

        val detectionIds: List<RetrievableId> = detections.map { it.id }
        val embeddingReader = embField.getReader()

        // Map retrievableId → embedding vector (keep only first descriptor per detection)
        val embeddingByDetection: Map<RetrievableId, FloatVectorDescriptor> =
            embeddingReader.getAllForRetrievable(detectionIds)
                .groupBy { it.retrievableId!! }
                .mapValues { (_, descs) -> descs.first() }

        // Filter to detections that actually have an embedding
        val indexedDetectionIds = detectionIds.filter { it in embeddingByDetection }
        val embeddings = indexedDetectionIds.map { id ->
            embeddingByDetection[id]!!.vector.value.toList()
        }

        logger.info { "[Clustering] ${embeddings.size} detections have embeddings (${detections.size - embeddings.size} skipped)." }
        if (embeddings.isEmpty()) return emptyRun(params, startedAt, "NO_EMBEDDINGS")

        // call python HDBSCAN
        val clusterResp = callClusterEndpoint(params, indexedDetectionIds, embeddings)
            ?: return emptyRun(params, startedAt, "PYTHON_ERROR")

        val labels: List<Int> = clusterResp.labels
        val nClusters = clusterResp.n_clusters
        logger.info { "[Clustering] HDBSCAN returned $nClusters clusters." }

        // persist results
        val writer = schema.connection.getRetrievableWriter()
        val runId = UUID.randomUUID()
        val completedAt = Instant.now().toString()

        // FACE_CLUSTER_RUN retrievable (type encodes run identity; metadata queried via relationships)
        writer.add(Ingested(runId, "FACE_CLUSTER_RUN", transient = false))

        // Group detections by cluster label (ignore noise label −1)
        val clusterGroups: Map<Int, List<RetrievableId>> = labels
            .withIndex()
            .filter { (_, label) -> label >= 0 }
            .groupBy({ (_, label) -> label }, { (i, _) -> indexedDetectionIds[i] })

        for ((_, memberIds) in clusterGroups) {
            val clusterId = UUID.randomUUID()
            writer.add(Ingested(clusterId, "FACE_CLUSTER", transient = false))

            // FACE_CLUSTER producedByRun FACE_CLUSTER_RUN
            writer.connect(Relationship.ById(clusterId, "producedByRun", runId, transient = false))

            // FACE_DETECTION memberOfCluster FACE_CLUSTER  (one relationship per member)
            val memberships = memberIds.map { detId ->
                Relationship.ById(detId, "memberOfCluster", clusterId, transient = false)
            }
            writer.connectAll(memberships)
        }

        val numAssigned = labels.count { it >= 0 }
        val numNoise = labels.count { it < 0 }

        logger.info { "[Clustering] Run $runId: $nClusters clusters, $numAssigned assigned, $numNoise noise." }

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
            startedAt = startedAt,
            completedAt = completedAt,
            status = "COMPLETED",
        )
    }

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
                logger.error { "[Clustering] Python server returned HTTP ${resp.statusCode()} from $url" }
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
            startedAt = startedAt,
            completedAt = Instant.now().toString(),
            status = status,
        )
}
