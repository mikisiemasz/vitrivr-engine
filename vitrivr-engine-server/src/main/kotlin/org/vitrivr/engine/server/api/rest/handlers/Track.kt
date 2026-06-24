package org.vitrivr.engine.server.api.rest.handlers

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.http.Context
import io.javalin.openapi.*
import kotlinx.serialization.Serializable
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.core.model.relationship.Relationship
import org.vitrivr.engine.module.features.feature.external.ExternalAnalyser.Companion.HOST_PARAMETER_DEFAULT
import org.vitrivr.engine.module.features.feature.external.ExternalAnalyser.Companion.HOST_PARAMETER_NAME
import org.vitrivr.engine.server.api.rest.model.ErrorStatus
import org.vitrivr.engine.server.api.rest.model.ErrorStatusException
import org.vitrivr.engine.server.services.FaceTrackingService
import org.vitrivr.engine.server.services.TrackingParams
import java.util.UUID

private val logger: KLogger = KotlinLogging.logger {}

@Serializable
data class TrackingRunSummary(
    val runId: String,
    val numInputDetections: Int,
    val numDroppedDetections: Int,
    val numVideos: Int,
    val numTracks: Int,
    val startedAt: String,
    val completedAt: String,
    val status: String,
)

@OpenApi(
    path = "/api/{schema}/tracks/run",
    methods = [HttpMethod.POST],
    summary = "Triggers a new face-tracking run. Blocking — returns when the run is complete.",
    operationId = "triggerTracking",
    tags = ["Track"],
    pathParams = [OpenApiParam("schema", type = String::class, required = true)],
    queryParams = [
        OpenApiParam("alpha", type = Float::class, description = "Appearance vs. IoU weight (default 0.85)."),
        OpenApiParam("threshold", type = Float::class, description = "Hard cosine floor (default 0.55)."),
        OpenApiParam("maxGapSec", type = Float::class, description = "Close a track after this many idle seconds (default 60)."),
        OpenApiParam("pythonServer", type = String::class),
    ],
    responses = [
        OpenApiResponse("200", [OpenApiContent(TrackingRunSummary::class)]),
        OpenApiResponse("500", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun triggerTracking(ctx: Context, schema: Schema) {
    /* Reuse the same host resolution chain as clustering: explicit query param overrides the
       face field's configured host, which overrides the global default. */
    val resolvedHost = ctx.queryParam("pythonServer")
        ?: schema["face"]?.parameters?.get(HOST_PARAMETER_NAME)
        ?: HOST_PARAMETER_DEFAULT

    val params = TrackingParams(
        alpha = ctx.queryParam("alpha")?.toFloatOrNull() ?: 0.85f,
        threshold = ctx.queryParam("threshold")?.toFloatOrNull() ?: 0.55f,
        maxGapSec = ctx.queryParam("maxGapSec")?.toFloatOrNull() ?: 60f,
        pythonServerUrl = resolvedHost,
    )
    logger.info { "Tracking run triggered for schema '${schema.name}' params=$params" }

    val result = try {
        FaceTrackingService(schema).run(params)
    } catch (e: Exception) {
        throw ErrorStatusException(500, "Tracking failed: ${e.message}")
    }

    ctx.json(
        TrackingRunSummary(
            runId = result.runId.toString(),
            numInputDetections = result.numInputDetections,
            numDroppedDetections = result.numDroppedDetections,
            numVideos = result.numVideos,
            numTracks = result.numTracks,
            startedAt = result.startedAt,
            completedAt = result.completedAt,
            status = result.status,
        )
    )
}

/** Light summary for the listing endpoint. Avoids re-deriving aggregate stats; UI displays runId + count. */
@Serializable
data class TrackRunListItem(
    val runId: String,
    val numTracks: Int,
)

@OpenApi(
    path = "/api/{schema}/tracks/runs",
    methods = [HttpMethod.GET],
    summary = "Lists all FACE_TRACK_RUN retrievables for the given schema, in insertion order.",
    operationId = "listTrackRuns",
    tags = ["Track"],
    pathParams = [OpenApiParam("schema", type = String::class, required = true)],
    responses = [OpenApiResponse("200", [OpenApiContent(Array<TrackRunListItem>::class)])]
)
fun listTrackRuns(ctx: Context, schema: Schema) {
    val reader = schema.connection.getRetrievableReader()
    val runs = reader.getAll("FACE_TRACK_RUN").map { run ->
        val trackCount = reader.getConnections(
            subjectIds = emptyList(),
            predicates = listOf("producedByTrackRun"),
            objectIds = listOf(run.id),
        ).count()
        TrackRunListItem(runId = run.id.toString(), numTracks = trackCount)
    }.toList()
    ctx.json(runs)
}

@OpenApi(
    path = "/api/{schema}/tracks/runs/{runId}",
    methods = [HttpMethod.DELETE],
    summary = "Deletes a FACE_TRACK_RUN, every FACE_TRACK it produced (with their trackEmbedding/trackMeta " +
              "descriptors), and the partOfTrack edges that linked face detections to those tracks. " +
              "FACE_DETECTIONs themselves are preserved.",
    operationId = "deleteTrackRun",
    tags = ["Track"],
    pathParams = [
        OpenApiParam("schema", type = String::class, required = true),
        OpenApiParam("runId", type = String::class, required = true),
    ],
    responses = [
        OpenApiResponse("204"),
        OpenApiResponse("404", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun deleteTrackRun(ctx: Context, schema: Schema) {
    val runId = runCatching { UUID.fromString(ctx.pathParam("runId")) }
        .getOrElse { throw ErrorStatusException(400, "Invalid runId UUID.") }
    val reader = schema.connection.getRetrievableReader()
    val writer = schema.connection.getRetrievableWriter()
    val runRetrievable = reader.get(runId)
        ?: throw ErrorStatusException(404, "Track run $runId not found.")

    val trackIds: List<UUID> = reader.getConnections(
        subjectIds = emptyList(),
        predicates = listOf("producedByTrackRun"),
        objectIds = listOf(runId),
    ).map { it.subjectId }.toList()

    /* Best-effort cleanup of related descriptor rows. The FK from descriptor.* → retrievable cascades
       on delete (ON DELETE CASCADE), so descriptor rows go away with their FACE_TRACK retrievable.
       But the pgvector module's writer.delete() removes just the retrievable row; we don't need to
       touch the descriptor tables explicitly. */

    var detachedMembers = 0
    for (tid in trackIds) {
        val track = reader.get(tid) ?: continue
        /* Disconnect every face's partOfTrack edge so the face detections are no longer linked to the
           soon-to-be-gone track. Faces themselves persist; only the edge dies. */
        val memberFaceIds = reader.getConnections(
            subjectIds = emptyList(),
            predicates = listOf("partOfTrack"),
            objectIds = listOf(tid),
        ).map { it.subjectId }.toList()
        if (memberFaceIds.isNotEmpty()) {
            writer.disconnectAll(memberFaceIds.map { Relationship.ById(it, "partOfTrack", tid, transient = false) })
            detachedMembers += memberFaceIds.size
        }
        writer.disconnectAll(listOf(Relationship.ById(tid, "producedByTrackRun", runId, transient = false)))
        writer.delete(track)
    }

    writer.delete(runRetrievable)
    logger.info { "[Track] Deleted run $runId (${trackIds.size} tracks, $detachedMembers partOfTrack edges detached)." }
    ctx.status(204)
}
