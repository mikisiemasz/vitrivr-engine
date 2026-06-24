package org.vitrivr.engine.server.services

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.vitrivr.engine.core.model.descriptor.struct.identity.TrackMetaDescriptor
import org.vitrivr.engine.core.model.descriptor.struct.metadata.TemporalMetadataDescriptor
import org.vitrivr.engine.core.model.descriptor.vector.FloatVectorDescriptor
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.core.model.relationship.Relationship
import org.vitrivr.engine.core.model.retrievable.Ingested
import org.vitrivr.engine.core.model.retrievable.RetrievableId
import org.vitrivr.engine.core.model.types.Value
import org.vitrivr.engine.module.features.feature.external.ExternalAnalyser.Companion.HOST_PARAMETER_DEFAULT
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID

private val logger: KLogger = KotlinLogging.logger {}

data class TrackingParams(
    val embeddingFieldName: String = "face",
    val bboxFieldName: String = "facebbox",
    val timeFieldName: String = "time",
    val trackEmbeddingFieldName: String = "trackEmbedding",
    val trackMetaFieldName: String = "trackMeta",
    val alpha: Float = 0.85f,
    val threshold: Float = 0.55f,
    val maxGapSec: Float = 60f,
    val pythonServerUrl: String = HOST_PARAMETER_DEFAULT,
)

data class TrackingRunInfo(
    val runId: UUID,
    /** Detections actually fed to the tracker (after dropping rows missing embedding/bbox/segment/source/time). */
    val numInputDetections: Int,
    /** Detections present in the DB but skipped because of missing pieces; lets the caller spot data-integrity holes. */
    val numDroppedDetections: Int,
    val numVideos: Int,
    val numTracks: Int,
    val startedAt: String,
    val completedAt: String,
    val status: String,
)

/* ── JSON DTOs for the Python /track/face_tracks contract ──────────────────────── */

@Serializable
private data class TrackDetectionRequest(
    val face_id: String,
    val time_ns: Long,
    val bbox: List<Float>,
    val embedding: List<Float>,
)

@Serializable
private data class TrackVideoRequest(val detections: List<TrackDetectionRequest>)

@Serializable
private data class TrackRequest(
    val videos: Map<String, TrackVideoRequest>,
    val alpha: Float,
    val threshold: Float,
    val max_gap_sec: Float,
)

@Serializable
private data class TrackResultItem(
    val track_id: Int,
    val start_ns: Long,
    val end_ns: Long,
    val detection_count: Int,
    val member_face_ids: List<String>,
    val mean_embedding: List<Float>,
)

@Serializable
private data class TrackVideoResponse(val tracks: List<TrackResultItem>)

@Serializable
private data class TrackResponse(val videos: Map<String, TrackVideoResponse>)

/**
 * Orchestrates one face-tracking run:
 *
 * 1. Loads every FACE_DETECTION retrievable, its face embedding, its bbox, the parent
 *    segment's `time` (for time_ns) and the segment's parent SOURCE id.
 * 2. Groups detections per source video and POSTs them to the Python `/track/face_tracks`
 *    endpoint, which returns appearance-driven tracks.
 * 3. For every track, materialises a FACE_TRACK retrievable + `trackEmbedding` (mean centroid)
 *    + `trackMeta` (source_id, start_ns, end_ns, detection_count), and connects each member
 *    FACE_DETECTION via a `partOfTrack` relationship. The track is also linked to the run via
 *    `producedByTrackRun`.
 *
 * Re-runnable: a fresh FACE_TRACK_RUN is created on each call; old tracks are left in place.
 * (Run-aware filtering is a job for the listing endpoint, mirroring FACE_CLUSTER_RUN semantics.)
 */
class FaceTrackingService(private val schema: Schema) {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newHttpClient()

    fun run(params: TrackingParams): TrackingRunInfo {
        val startedAt = Instant.now().toString()
        val reader = schema.connection.getRetrievableReader()
        val writer = schema.connection.getRetrievableWriter()

        @Suppress("UNCHECKED_CAST")
        val embField = schema[params.embeddingFieldName] as? Schema.Field<*, FloatVectorDescriptor>
            ?: return emptyRun(startedAt, "EMBEDDING_FIELD_MISSING")
        @Suppress("UNCHECKED_CAST")
        val bboxField = schema[params.bboxFieldName] as? Schema.Field<*, FloatVectorDescriptor>
            ?: return emptyRun(startedAt, "BBOX_FIELD_MISSING")
        @Suppress("UNCHECKED_CAST")
        val timeField = schema[params.timeFieldName] as? Schema.Field<*, TemporalMetadataDescriptor>
            ?: return emptyRun(startedAt, "TIME_FIELD_MISSING")
        @Suppress("UNCHECKED_CAST")
        val trackEmbField = schema[params.trackEmbeddingFieldName] as? Schema.Field<*, FloatVectorDescriptor>
            ?: return emptyRun(startedAt, "TRACK_EMBEDDING_FIELD_MISSING")
        @Suppress("UNCHECKED_CAST")
        val trackMetaField = schema[params.trackMetaFieldName] as? Schema.Field<*, TrackMetaDescriptor>
            ?: return emptyRun(startedAt, "TRACK_META_FIELD_MISSING")

        val detectionIds: List<RetrievableId> =
            reader.getAll("FACE_DETECTION").map { it.id }.toList()
        logger.info { "[Tracking] Found ${detectionIds.size} FACE_DETECTION retrievables." }
        if (detectionIds.isEmpty()) return emptyRun(startedAt, "NO_DETECTIONS")

        /* Bulk descriptor reads; Postgres' 65k-param cap → chunk to 30k. */
        val embeddingByFace: Map<UUID, FloatArray> = detectionIds.chunked(30_000).flatMap { batch ->
            embField.getReader().getAllForRetrievable(batch).toList()
        }.mapNotNull { d -> d.retrievableId?.let { it to d.vector.value } }.toMap()

        val bboxByFace: Map<UUID, FloatArray> = detectionIds.chunked(30_000).flatMap { batch ->
            bboxField.getReader().getAllForRetrievable(batch).toList()
        }.mapNotNull { d -> d.retrievableId?.let { it to d.vector.value } }.toMap()

        /* face → segment via partOf. */
        val faceToSegment: Map<UUID, UUID> = detectionIds.chunked(30_000).flatMap { batch ->
            reader.getConnections(
                subjectIds = batch,
                predicates = listOf("partOf"),
                objectIds = emptyList(),
            ).map { it.subjectId to it.objectId }.toList()
        }.toMap()

        val segmentIds = faceToSegment.values.toSet().toList()

        /* segment → source via partOf. */
        val segmentToSource: Map<UUID, UUID> = segmentIds.chunked(30_000).flatMap { batch ->
            reader.getConnections(
                subjectIds = batch,
                predicates = listOf("partOf"),
                objectIds = emptyList(),
            ).map { it.subjectId to it.objectId }.toList()
        }.toMap()

        /* segment → time_ns (start). */
        val segmentStartNs: Map<UUID, Long> = segmentIds.chunked(30_000).flatMap { batch ->
            timeField.getReader().getAllForRetrievable(batch).toList()
        }.mapNotNull { d -> d.retrievableId?.let { it to d.start.value } }.toMap()

        /* Assemble per-video detection lists. Skip detections missing any required piece. */
        data class Det(val faceId: UUID, val timeNs: Long, val bbox: FloatArray, val emb: FloatArray)
        val perVideo = HashMap<UUID, MutableList<Det>>()
        var dropped = 0
        for (fid in detectionIds) {
            val emb = embeddingByFace[fid]
            val bbox = bboxByFace[fid]
            val seg = faceToSegment[fid]
            val src = seg?.let { segmentToSource[it] }
            val tns = seg?.let { segmentStartNs[it] }
            if (emb == null || bbox == null || src == null || tns == null) {
                dropped++
                continue
            }
            perVideo.getOrPut(src) { mutableListOf() }.add(Det(fid, tns, bbox, emb))
        }
        if (dropped > 0) logger.warn { "[Tracking] Dropped $dropped detections missing embedding/bbox/segment/source/time." }
        if (perVideo.isEmpty()) return emptyRun(startedAt, "NO_USABLE_DETECTIONS")

        val totalUsable = perVideo.values.sumOf { it.size }
        logger.info { "[Tracking] Tracking $totalUsable detections across ${perVideo.size} videos." }

        /* Build the request DTO. */
        val payload = TrackRequest(
            videos = perVideo.mapKeys { it.key.toString() }.mapValues { (_, dets) ->
                TrackVideoRequest(dets.map { d ->
                    TrackDetectionRequest(
                        face_id = d.faceId.toString(),
                        time_ns = d.timeNs,
                        bbox = d.bbox.toList(),
                        embedding = d.emb.toList(),
                    )
                })
            },
            alpha = params.alpha,
            threshold = params.threshold,
            max_gap_sec = params.maxGapSec,
        )

        val response = callTrackEndpoint(params.pythonServerUrl, payload)
            ?: return emptyRun(startedAt, "PYTHON_ERROR")

        /* Materialise the run + tracks. */
        val runId = UUID.randomUUID()
        writer.add(Ingested(runId, "FACE_TRACK_RUN", transient = false))

        val trackEmbWriter = trackEmbField.getWriter()
        val trackMetaWriter = trackMetaField.getWriter()

        var totalTracks = 0
        for ((sourceIdStr, vresp) in response.videos) {
            for (tr in vresp.tracks) {
                val trackId = UUID.randomUUID()
                writer.add(Ingested(trackId, "FACE_TRACK", transient = false))
                writer.connect(Relationship.ById(trackId, "producedByTrackRun", runId, transient = false))

                /* partOfTrack: face → track. */
                var malformedMembers = 0
                val memberRels = tr.member_face_ids.mapNotNull { idStr ->
                    val parsed = runCatching { UUID.fromString(idStr) }.getOrNull()
                    if (parsed == null) { malformedMembers++; null }
                    else Relationship.ById(parsed, "partOfTrack", trackId, transient = false)
                }
                if (malformedMembers > 0) {
                    logger.warn { "[Tracking] Track $trackId: dropped $malformedMembers member id(s) that failed UUID parsing." }
                }
                if (memberRels.isNotEmpty()) writer.connectAll(memberRels)

                trackEmbWriter.add(
                    FloatVectorDescriptor(
                        id = UUID.randomUUID(),
                        retrievableId = trackId,
                        vector = Value.FloatVector(tr.mean_embedding.toFloatArray()),
                        field = trackEmbField,
                    )
                )
                trackMetaWriter.add(
                    TrackMetaDescriptor(
                        id = UUID.randomUUID(),
                        retrievableId = trackId,
                        sourceId = sourceIdStr,
                        startNs = tr.start_ns,
                        endNs = tr.end_ns,
                        detectionCount = tr.detection_count,
                        field = trackMetaField,
                    )
                )
                totalTracks++
            }
        }

        val completedAt = Instant.now().toString()
        logger.info { "[Tracking] Run $runId: $totalTracks tracks across ${response.videos.size} videos." }

        return TrackingRunInfo(
            runId = runId,
            numInputDetections = totalUsable,
            numDroppedDetections = dropped,
            numVideos = perVideo.size,
            numTracks = totalTracks,
            startedAt = startedAt,
            completedAt = completedAt,
            status = "COMPLETED",
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun callTrackEndpoint(serverUrl: String, payload: TrackRequest): TrackResponse? {
        val url = "${serverUrl.trimEnd('/')}/track/face_tracks"
        val body = json.encodeToString(payload)
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
            if (resp.statusCode() !in 200..299) {
                logger.error { "[Tracking] Python server HTTP ${resp.statusCode()} from $url" }
                return null
            }
            json.decodeFromStream<TrackResponse>(resp.body())
        } catch (e: Exception) {
            logger.error(e) { "[Tracking] Failed to call $url" }
            null
        }
    }

    private fun emptyRun(startedAt: String, status: String): TrackingRunInfo = TrackingRunInfo(
        runId = UUID.randomUUID(),
        numInputDetections = 0,
        numDroppedDetections = 0,
        numVideos = 0,
        numTracks = 0,
        startedAt = startedAt,
        completedAt = Instant.now().toString(),
        status = status,
    )
}
