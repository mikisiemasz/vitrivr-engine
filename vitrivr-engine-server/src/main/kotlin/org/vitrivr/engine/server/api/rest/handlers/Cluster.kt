package org.vitrivr.engine.server.api.rest.handlers

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.http.Context
import io.javalin.http.bodyAsClass
import io.javalin.openapi.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.vitrivr.engine.core.model.descriptor.struct.metadata.TemporalMetadataDescriptor
import org.vitrivr.engine.core.model.descriptor.struct.metadata.source.FileSourceMetadataDescriptor
import org.vitrivr.engine.core.model.descriptor.vector.FloatVectorDescriptor
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.core.model.relationship.Relationship
import org.vitrivr.engine.core.model.retrievable.Ingested
import org.vitrivr.engine.core.model.retrievable.RetrievableId
import org.vitrivr.engine.module.features.feature.external.ExternalAnalyser.Companion.HOST_PARAMETER_DEFAULT
import org.vitrivr.engine.module.features.feature.external.ExternalAnalyser.Companion.HOST_PARAMETER_NAME
import org.vitrivr.engine.server.api.rest.model.ErrorStatus
import org.vitrivr.engine.server.api.rest.model.ErrorStatusException
import org.vitrivr.engine.server.api.rest.model.cluster.*
import org.vitrivr.engine.server.services.ClusterStore
import org.vitrivr.engine.server.services.ClusteringParams
import org.vitrivr.engine.server.services.ClusteringTarget
import org.vitrivr.engine.server.services.FaceClusteringService
import java.util.*

private val logger: KLogger = KotlinLogging.logger {}

private fun parseUuidOrThrow(raw: String, label: String): UUID = runCatching { UUID.fromString(raw) }
    .getOrElse { throw ErrorStatusException(400, "Invalid $label UUID: '$raw'.") }

private fun memberIdsOf(schema: Schema, clusterId: UUID): List<UUID> =
    schema.connection.getRetrievableReader().getConnections(
        subjectIds = emptyList(),
        predicates = listOf("memberOfCluster"),
        objectIds = listOf(clusterId),
    ).map { it.subjectId }.toList()

private fun exemplarIdsOf(schema: Schema, clusterId: UUID): List<UUID> =
    schema.connection.getRetrievableReader().getConnections(
        subjectIds = emptyList(),
        predicates = listOf("exemplarOfCluster"),
        objectIds = listOf(clusterId),
    ).map { it.subjectId }.toList()

private fun parentMapOf(schema: Schema, faceIds: Collection<UUID>): Map<UUID, UUID> {
    if (faceIds.isEmpty()) return emptyMap()
    return schema.connection.getRetrievableReader().getConnections(
        subjectIds = faceIds,
        predicates = listOf("partOf"),
        objectIds = emptyList(),
    ).associate { it.subjectId to it.objectId }
}

/**
 * Returns one FACE_DETECTION id per input FACE_TRACK id, via the `partOfTrack` relationship.
 * Picks any face per track (first one returned by the bulk query). Tracks that have no member
 * detections are silently dropped.
 *
 * Cheap unifier used by every handler that needs to render or score track-cluster exemplars.
 * Could be upgraded to "closest to track centroid" later — requires loading embeddings.
 */
private fun representativeFaceByTrack(schema: Schema, trackIds: Collection<UUID>): Map<UUID, UUID> {
    if (trackIds.isEmpty()) return emptyMap()
    val pairs = trackIds.chunked(30_000).flatMap { batch ->
        schema.connection.getRetrievableReader().getConnections(
            subjectIds = emptyList(),
            predicates = listOf("partOfTrack"),
            objectIds = batch,
        ).map { it.subjectId to it.objectId }.toList()
    }
    /* group by track; pick first face seen per track. */
    return pairs.groupBy { it.second }.mapValues { (_, list) -> list.first().first }
}

/**
 * Returns the underlying FACE_DETECTION ids for a cluster.
 * - Detection clusters: direct 'memberOfCluster' members.
 * - Track clusters: members are FACE_TRACKs; expanded via `partOfTrack` to the faces inside.
 *
 * Single unifier so every downstream walk (segments, timeline, match, co-occurrences) only needs
 * to handle face ids; the cluster-type branch lives here.
 */
private fun detectionIdsOfCluster(schema: Schema, clusterId: UUID): List<UUID> {
    val reader = schema.connection.getRetrievableReader()
    val memberIds = memberIdsOf(schema, clusterId)
    if (memberIds.isEmpty()) return emptyList()
    val memberType = reader.get(memberIds.first())?.type ?: "FACE_DETECTION"
    if (memberType != "FACE_TRACK") return memberIds
    /* Track cluster: expand each track to its member faces via partOfTrack (face → track edges). */
    return memberIds.chunked(30_000).flatMap { batch ->
        reader.getConnections(
            subjectIds = emptyList(),
            predicates = listOf("partOfTrack"),
            objectIds = batch,
        ).map { it.subjectId }.toList()
    }
}

/**
 * Set of FACE_CLUSTER ids whose members are of the given retrievable type ("FACE_DETECTION" or
 * "FACE_TRACK"). Used to scope identify / centroid scans to one clustering kind so detection-clusters
 * and track-clusters aren't compared in the same ranking.
 *
 * One pass over all memberOfCluster edges + one id-scan of the target type. A cluster is classified
 * by the type of any one of its members (uniform within a cluster).
 */
private fun clusterIdsByTarget(schema: Schema, memberType: String): Set<UUID> {
    val reader = schema.connection.getRetrievableReader()
    val typeIds = reader.getAll(memberType).map { it.id }.toHashSet()
    if (typeIds.isEmpty()) return emptySet()
    return reader.getConnections(
        subjectIds = emptyList(),
        predicates = listOf("memberOfCluster"),
        objectIds = emptyList(),
    ).groupBy { it.objectId }
        .filterValues { rels -> rels.first().subjectId in typeIds }
        .keys
}

/** Parses a `target` query param into a member retrievable type, or null when absent/"all". */
private fun parseTargetParam(raw: String?): String? = raw?.lowercase()?.let {
    when (it) {
        "detections", "detection" -> "FACE_DETECTION"
        "tracks", "track" -> "FACE_TRACK"
        "all", "" -> null
        else -> throw ErrorStatusException(400, "Invalid target '$it'; expected 'detections', 'tracks', or 'all'.")
    }
}

/** Bundle of frontend-display metadata for a SEGMENT hit. */
private data class SegmentDisplayInfo(
    val sourceId: UUID?,
    val filePath: String?,
    val startNs: Long?,
    val endNs: Long?,
)

/**
 * Each lookup is independent; missing fields just yield null in the result.
 */
private fun segmentDisplayInfoFor(
    schema: Schema,
    segmentIds: Collection<UUID>,
): Map<UUID, SegmentDisplayInfo> {
    if (segmentIds.isEmpty()) return emptyMap()
    val reader = schema.connection.getRetrievableReader()

    /* segment -> source (segment's partOf relation). */
    val segToSource: Map<UUID, UUID> = segmentIds.chunked(30_000).flatMap { batch ->
        reader.getConnections(
            subjectIds = batch,
            predicates = listOf("partOf"),
            objectIds = emptyList(),
        ).map { it.subjectId to it.objectId }.toList()
    }.toMap()

    /* source -> file.path, only when the schema has the field configured. */
    @Suppress("UNCHECKED_CAST")
    val fileField = schema["file"] as? Schema.Field<*, FileSourceMetadataDescriptor>
    val sourceToPath: Map<UUID, String> = if (fileField != null) {
        val sourceIds = segToSource.values.toSet().toList()
        sourceIds.chunked(30_000).flatMap { batch ->
            fileField.getReader().getAllForRetrievable(batch).toList()
        }.mapNotNull { d -> d.retrievableId?.let { it to d.path.value } }.toMap()
    } else {
        emptyMap()
    }

    /* segment -> (start, end) nanoseconds, from the segment's own TemporalMetadata. */
    @Suppress("UNCHECKED_CAST")
    val timeField = schema["time"] as? Schema.Field<*, TemporalMetadataDescriptor>
    val segToTime: Map<UUID, Pair<Long, Long>> = if (timeField != null) {
        segmentIds.chunked(30_000).flatMap { batch ->
            timeField.getReader().getAllForRetrievable(batch).toList()
        }.mapNotNull { d -> d.retrievableId?.let { it to (d.start.value to d.end.value) } }.toMap()
    } else {
        emptyMap()
    }

    return segmentIds.associateWith { segId ->
        val srcId = segToSource[segId]
        val time = segToTime[segId]
        SegmentDisplayInfo(
            sourceId = srcId,
            filePath = srcId?.let { sourceToPath[it] },
            startNs = time?.first,
            endNs = time?.second,
        )
    }
}

@OpenApi(
    path = "/api/{schema}/clusters/runs",
    methods = [HttpMethod.GET],
    summary = "Lists all FACE_CLUSTER_RUN retrievables for the given schema.",
    operationId = "listClusterRuns",
    tags = ["Cluster"],
    pathParams = [OpenApiParam("schema", type = String::class, required = true)],
    responses = [OpenApiResponse("200", [OpenApiContent(Array<ClusterRunSummary>::class)])]
)
fun listClusterRuns(ctx: Context, schema: Schema) {
    val reader = schema.connection.getRetrievableReader()
    val runs = reader.getAll("FACE_CLUSTER_RUN").map { run ->
        /* Infer target by walking the run → one cluster → one member → member.type. Avoids needing a
           dedicated descriptor on the run retrievable to record what kind of clustering it was. */
        val (target, embeddingField) = run {
            val anyCluster = reader.getConnections(
                subjectIds = emptyList(),
                predicates = listOf("producedByRun"),
                objectIds = listOf(run.id),
            ).map { it.subjectId }.firstOrNull()
            val anyMemberType = anyCluster?.let { c ->
                reader.getConnections(
                    subjectIds = emptyList(),
                    predicates = listOf("memberOfCluster"),
                    objectIds = listOf(c),
                ).map { it.subjectId }.firstOrNull()?.let { reader.get(it)?.type }
            }
            when (anyMemberType) {
                "FACE_TRACK" -> "tracks" to "trackEmbedding"
                else -> "detections" to "face"
            }
        }
        ClusterRunSummary(
            runId = run.id.toString(),
            algorithm = "HDBSCAN",
            target = target,
            embeddingField = embeddingField,
            minClusterSize = -1,
            minSamples = -1,
            numInputFaces = -1,
            numAssignedFaces = -1,
            numNoiseFaces = -1,
            numClusters = -1,
            startedAt = "",
            completedAt = "",
            status = "COMPLETED",
        )
    }.toList()
    ctx.contentType("application/json").result(
        Json.encodeToString(ListSerializer(ClusterRunSummary.serializer()), runs)
    )
}

@OpenApi(
    path = "/api/{schema}/clusters/run",
    methods = [HttpMethod.POST],
    summary = "Triggers a new face clustering run. Blocking — returns when the run is complete.",
    operationId = "triggerClustering",
    tags = ["Cluster"],
    pathParams = [OpenApiParam("schema", type = String::class, required = true)],
    queryParams = [
        OpenApiParam("embeddingField", type = String::class),
        OpenApiParam("minClusterSize", type = Int::class),
        OpenApiParam("minSamples", type = Int::class),
        OpenApiParam("exemplarCount", type = Int::class),
        OpenApiParam("labelCarryThreshold", type = Float::class),
        OpenApiParam("pythonServer", type = String::class),
    ],
    responses = [
        OpenApiResponse("200", [OpenApiContent(ClusterRunSummary::class)]),
        OpenApiResponse("500", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun triggerClustering(ctx: Context, schema: Schema) {
    val target = when (ctx.queryParam("target")?.lowercase()) {
        "tracks", "track" -> ClusteringTarget.TRACKS
        "detections", "detection", null -> ClusteringTarget.DETECTIONS
        else -> throw ErrorStatusException(400, "Invalid target '${ctx.queryParam("target")}'; expected 'detections' or 'tracks'.")
    }
    /* Embedding field defaults are target-aware (face vs trackEmbedding) and resolved inside ClusteringParams. */
    val embeddingFieldName = ctx.queryParam("embeddingField")
    /* Host resolution still keys off the underlying face model's "host" param — tracking and detection both go to
       the same Python server, so the face field's host is the right default in both modes. */
    val resolvedHost = ctx.queryParam("pythonServer")
        ?: schema["face"]?.parameters?.get(HOST_PARAMETER_NAME)
        ?: HOST_PARAMETER_DEFAULT

    val params = ClusteringParams(
        target = target,
        embeddingFieldName = embeddingFieldName,
        minClusterSize = ctx.queryParam("minClusterSize")?.toIntOrNull() ?: 5,
        minSamples = ctx.queryParam("minSamples")?.toIntOrNull() ?: 3,
        exemplarCount = ctx.queryParam("exemplarCount")?.toIntOrNull() ?: 5,
        labelCarryThreshold = ctx.queryParam("labelCarryThreshold")?.toFloatOrNull() ?: 0.6f,
        pythonServerUrl = resolvedHost,
    )
    logger.info { "Clustering run triggered for schema '${schema.name}' params=$params" }

    val result = try {
        FaceClusteringService(schema).run(params)
    } catch (e: Exception) {
        throw ErrorStatusException(500, "Clustering failed: ${e.message}")
    }

    ctx.json(
        ClusterRunSummary(
            runId = result.runId.toString(),
            algorithm = result.algorithm,
            target = result.target.name.lowercase(),
            embeddingField = result.embeddingField,
            minClusterSize = result.minClusterSize,
            minSamples = result.minSamples,
            numInputFaces = result.numInputFaces,
            numAssignedFaces = result.numAssignedFaces,
            numNoiseFaces = result.numNoiseFaces,
            numClusters = result.numClusters,
            numLabelsCarried = result.numLabelsCarried,
            startedAt = result.startedAt,
            completedAt = result.completedAt,
            status = result.status,
        )
    )
}

@OpenApi(
    path = "/api/{schema}/clusters",
    methods = [HttpMethod.GET],
    summary = "Returns a paginated gallery of FACE_CLUSTER retrievables, optionally filtered to one run.",
    operationId = "listClusters",
    tags = ["Cluster"],
    pathParams = [OpenApiParam("schema", type = String::class, required = true)],
    queryParams = [
        OpenApiParam("runId", type = String::class),
        OpenApiParam("target", type = String::class, description = "detections | tracks | all (default: all)"),
        OpenApiParam("limit", type = Int::class),
        OpenApiParam("offset", type = Int::class),
        OpenApiParam("minMembers", type = Int::class),
        OpenApiParam("sort", type = String::class, description = "members | segments | label"),
        OpenApiParam("onlyLabelled", type = Boolean::class),
        OpenApiParam("onlyUnlabelled", type = Boolean::class),
    ],
    responses = [OpenApiResponse("200", [OpenApiContent(ClusterGalleryResponse::class)])]
)
fun listClusters(ctx: Context, schema: Schema) {
    val runIdFilter = ctx.queryParam("runId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    val limit = ctx.queryParam("limit")?.toIntOrNull()?.coerceAtLeast(1) ?: 50
    val offset = ctx.queryParam("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val minMembers = ctx.queryParam("minMembers")?.toIntOrNull() ?: 1
    val sort = ctx.queryParam("sort") ?: "members"
    val onlyLabelled = ctx.queryParam("onlyLabelled")?.toBoolean() ?: false
    val onlyUnlabelled = ctx.queryParam("onlyUnlabelled")?.toBoolean() ?: false
    /* Optional target filter: restrict the gallery to detection-clusters (FACE_DETECTION members) or
       track-clusters (FACE_TRACK members). Omit / pass "all" to see everything (default). */
    val targetFilter: String? = ctx.queryParam("target")?.lowercase()?.let {
        when (it) {
            "detections", "detection" -> "FACE_DETECTION"
            "tracks", "track" -> "FACE_TRACK"
            "all", "" -> null
            else -> throw ErrorStatusException(400, "Invalid target '$it'; expected 'detections', 'tracks', or 'all'.")
        }
    }

    val reader = schema.connection.getRetrievableReader()

    val clusterIds: List<UUID> = if (runIdFilter != null) {
        reader.getConnections(
            subjectIds = emptyList(),
            predicates = listOf("producedByRun"),
            objectIds = listOf(runIdFilter),
        ).map { it.subjectId }.toList()
    } else {
        reader.getAll("FACE_CLUSTER").map { it.id }.toList()
    }

    val labels = ClusterStore.forSchema(schema).allLabels()

    val items = clusterIds.mapNotNull { clusterId ->
        val memberIds = memberIdsOf(schema, clusterId)
        if (memberIds.size < minMembers) return@mapNotNull null

        val label = labels[clusterId]
        if (onlyLabelled && label == null) return@mapNotNull null
        if (onlyUnlabelled && label != null) return@mapNotNull null

        /* Determine member type from any one member; uniform within a cluster. */
        val memberType = memberIds.firstOrNull()?.let { reader.get(it)?.type } ?: "FACE_DETECTION"
        if (targetFilter != null && memberType != targetFilter) return@mapNotNull null

        val exemplarIds = exemplarIdsOf(schema, clusterId).ifEmpty { memberIds.take(3) }

        if (memberType == "FACE_TRACK") {
            /* Track cluster: expand all member tracks to their face detections to compute segmentCount.
               Exemplars stay as track ids here — they get resolved to representative faces in the
               page-enrichment block below, where we bulk-fetch parent + bbox for the visible page. */
            val faceIds = detectionIdsOfCluster(schema, clusterId)
            val parentsAcrossTracks = parentMapOf(schema, faceIds)
            ClusterGalleryItem(
                clusterId = clusterId.toString(),
                memberCount = memberIds.size,
                memberType = memberType,
                exemplars = exemplarIds.map { ClusterExemplar(faceId = it.toString(), trackId = it.toString()) },
                label = label,
                segmentCount = parentsAcrossTracks.values.toSet().size,
            )
        } else {
            val parents = parentMapOf(schema, memberIds)
            ClusterGalleryItem(
                clusterId = clusterId.toString(),
                memberCount = memberIds.size,
                memberType = memberType,
                exemplars = exemplarIds.map { ClusterExemplar(faceId = it.toString(), parentId = parents[it]?.toString()) },
                label = label,
                segmentCount = parents.values.toSet().size,
            )
        }
    }

    val sorted = when (sort) {
        "segments" -> items.sortedByDescending { it.segmentCount }
        "label" -> items.sortedWith(compareBy({ it.label.isNullOrBlank() }, { it.label ?: "" }))
        else -> items.sortedByDescending { it.memberCount }
    }

    val total = sorted.size
    val page = sorted.drop(offset).take(limit)

    /* Enrich exemplars for the visible page only.
       - Detection clusters: bbox lookup against 'facebbox' keyed by exemplar face id.
       - Track clusters: first resolve every exemplar track to a representative FACE_DETECTION
         (any face in the track — could be upgraded to closest-to-centroid later), then the same
         parent + bbox lookups apply. After this step both kinds present uniform exemplars to the
         frontend: faceId points at a renderable detection, parentId at its segment, bbox at the
         face crop in that segment's frame. */
    @Suppress("UNCHECKED_CAST")
    val bboxField = schema["facebbox"] as? Schema.Field<*, FloatVectorDescriptor>

    val pageTrackExemplarIds: List<UUID> = page
        .filter { it.memberType == "FACE_TRACK" }
        .flatMap { it.exemplars.mapNotNull { e -> runCatching { UUID.fromString(e.faceId) }.getOrNull() } }
        .distinct()
    val repFaceByTrack = representativeFaceByTrack(schema, pageTrackExemplarIds)
    val repFaceParents = parentMapOf(schema, repFaceByTrack.values)

    val pageWithExemplars = page.map { item ->
        if (item.memberType != "FACE_TRACK") item
        else item.copy(
            exemplars = item.exemplars.map { ex ->
                val trackUuid = runCatching { UUID.fromString(ex.faceId) }.getOrNull()
                val rep = trackUuid?.let { repFaceByTrack[it] }
                if (rep == null) ex.copy(trackId = ex.faceId) else ex.copy(
                    faceId = rep.toString(),
                    parentId = repFaceParents[rep]?.toString(),
                    trackId = ex.faceId,
                )
            }
        )
    }

    val pageWithBbox: List<ClusterGalleryItem> = if (bboxField != null) {
        val pageExemplarFaceIds: List<UUID> = pageWithExemplars
            .flatMap { it.exemplars.mapNotNull { e -> runCatching { UUID.fromString(e.faceId) }.getOrNull() } }
            .distinct()
        val bboxByFace: Map<UUID, List<Float>> = pageExemplarFaceIds.chunked(30_000)
            .flatMap { batch -> bboxField.getReader().getAllForRetrievable(batch).toList() }
            .mapNotNull { d -> d.retrievableId?.let { it to d.vector.value.toList() } }
            .toMap()
        pageWithExemplars.map { item ->
            item.copy(
                exemplars = item.exemplars.map { ex ->
                    val faceUuid = runCatching { UUID.fromString(ex.faceId) }.getOrNull()
                    ex.copy(bbox = faceUuid?.let { bboxByFace[it] })
                }
            )
        }
    } else pageWithExemplars

    ctx.json(
        ClusterGalleryResponse(
            runId = runIdFilter?.toString() ?: "all",
            schema = schema.name,
            totalClusters = total,
            limit = limit,
            offset = offset,
            clusters = pageWithBbox,
        )
    )
}

@OpenApi(
    path = "/api/{schema}/clusters/{clusterId}/members",
    methods = [HttpMethod.GET],
    summary = "Returns a paginated list of FACE_DETECTION members for a given cluster.",
    operationId = "getClusterMembers",
    tags = ["Cluster"],
    pathParams = [
        OpenApiParam("schema", type = String::class, required = true),
        OpenApiParam("clusterId", type = String::class, required = true),
    ],
    queryParams = [OpenApiParam("limit", type = Int::class), OpenApiParam("offset", type = Int::class)],
    responses = [
        OpenApiResponse("200", [OpenApiContent(ClusterMemberPage::class)]),
        OpenApiResponse("404", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun getClusterMembers(ctx: Context, schema: Schema) {
    val clusterId = parseUuidOrThrow(ctx.pathParam("clusterId"), "clusterId")
    val limit = ctx.queryParam("limit")?.toIntOrNull()?.coerceAtLeast(1) ?: 100
    val offset = ctx.queryParam("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    if (schema.connection.getRetrievableReader().get(clusterId) == null) {
        throw ErrorStatusException(404, "Cluster $clusterId not found.")
    }

    /* For track-clusters, expand directly to the underlying face detections so the detail view
       can render the same bbox-on-segment-thumbnail grid as detection-clusters. The track structure
       above them is still preserved in the DB (and surfaced via getClusterTimeline). */
    val memberIds = detectionIdsOfCluster(schema, clusterId)
    val parents = parentMapOf(schema, memberIds)
    val pageIds = memberIds.drop(offset).take(limit)

    /* Load bbox descriptors for just this page's members, in chunks (Postgres' 65k param cap). */
    @Suppress("UNCHECKED_CAST")
    val bboxField = schema["facebbox"] as? Schema.Field<*, FloatVectorDescriptor>
    val bboxByMember: Map<UUID, List<Float>> = bboxField?.let { f ->
        val reader = f.getReader()
        pageIds.chunked(30_000)
            .flatMap { batch -> reader.getAllForRetrievable(batch).toList() }
            .mapNotNull { d -> d.retrievableId?.let { it to d.vector.value.toList() } }
            .toMap()
    } ?: emptyMap()

    val page = pageIds.map { faceId ->
        ClusterMemberItem(
            faceId = faceId.toString(),
            parentId = parents[faceId]?.toString(),
            bbox = bboxByMember[faceId],
        )
    }
    ctx.json(ClusterMemberPage(clusterId.toString(), memberIds.size, limit, offset, page))
}

@OpenApi(
    path = "/api/{schema}/clusters/{clusterId}/centroid",
    methods = [HttpMethod.GET],
    summary = "Returns the stored centroid embedding of a cluster, for use as a face-search query.",
    operationId = "getClusterCentroid",
    tags = ["Cluster"],
    pathParams = [
        OpenApiParam("schema", type = String::class, required = true),
        OpenApiParam("clusterId", type = String::class, required = true),
    ],
    responses = [
        OpenApiResponse("200", [OpenApiContent(ClusterCentroidResponse::class)]),
        OpenApiResponse("404", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun getClusterCentroid(ctx: Context, schema: Schema) {
    val clusterId = parseUuidOrThrow(ctx.pathParam("clusterId"), "clusterId")
    val store = ClusterStore.forSchema(schema)

    /* Fast path: centroid already persisted (descriptor or legacy sidecar). */
    store.getCentroid(clusterId)?.let {
        ctx.json(ClusterCentroidResponse(clusterId.toString(), it.toList()))
        return
    }

    /* Backfill: compute from members' embeddings, persist, and return.
       Needed for clusters from older runs that predate centroid persistence. The embedding field
       depends on cluster target: face for detection-clusters, trackEmbedding for track-clusters. */
    val memberIds = memberIdsOf(schema, clusterId)
    if (memberIds.isEmpty()) {
        throw ErrorStatusException(404, "Cluster $clusterId has no members; cannot compute centroid.")
    }
    val reader = schema.connection.getRetrievableReader()
    val memberType = reader.get(memberIds.first())?.type ?: "FACE_DETECTION"
    val embFieldName = if (memberType == "FACE_TRACK") "trackEmbedding" else "face"

    @Suppress("UNCHECKED_CAST")
    val embField = schema[embFieldName] as? Schema.Field<*, FloatVectorDescriptor>
        ?: throw ErrorStatusException(500, "Schema has no '$embFieldName' embedding field configured.")

    /* Postgres prepared-statement param cap; chunk the IN(...) the same way clustering does. */
    val vectors: List<FloatArray> = memberIds.chunked(30_000)
        .flatMap { batch -> embField.getReader().getAllForRetrievable(batch).toList() }
        .map { it.vector.value }

    if (vectors.isEmpty()) {
        throw ErrorStatusException(404, "Cluster $clusterId has members but no face embeddings.")
    }

    val centroid = FaceClusteringService.computeMeanNormalized(vectors)
    store.setCentroid(clusterId, centroid)
    logger.info { "[ClusterCentroid] Backfilled centroid for cluster $clusterId from ${vectors.size} members." }
    ctx.json(ClusterCentroidResponse(clusterId.toString(), centroid.toList()))
}

@OpenApi(
    path = "/api/{schema}/clusters/{clusterId}/segments",
    methods = [HttpMethod.GET],
    summary = "Returns segments (parent retrievables) containing this cluster's members, deduplicated.",
    operationId = "getClusterSegments",
    tags = ["Cluster"],
    pathParams = [
        OpenApiParam("schema", type = String::class, required = true),
        OpenApiParam("clusterId", type = String::class, required = true),
    ],
    queryParams = [OpenApiParam("limit", type = Int::class), OpenApiParam("offset", type = Int::class)],
    responses = [
        OpenApiResponse("200", [OpenApiContent(ClusterSegmentPage::class)]),
        OpenApiResponse("404", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun getClusterSegments(ctx: Context, schema: Schema) {
    val clusterId = parseUuidOrThrow(ctx.pathParam("clusterId"), "clusterId")
    val limit = ctx.queryParam("limit")?.toIntOrNull()?.coerceAtLeast(1) ?: 50
    val offset = ctx.queryParam("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    if (schema.connection.getRetrievableReader().get(clusterId) == null) {
        throw ErrorStatusException(404, "Cluster $clusterId not found.")
    }

    /* Use the helper so segment aggregation works the same for both cluster targets. */
    val faceIds = detectionIdsOfCluster(schema, clusterId)
    val parents = parentMapOf(schema, faceIds)
    val grouped = parents.values
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { ClusterSegmentItem(parentId = it.key.toString(), detectionCount = it.value) }

    val page = grouped.drop(offset).take(limit)
    ctx.json(ClusterSegmentPage(clusterId.toString(), grouped.size, limit, offset, page))
}

@OpenApi(
    path = "/api/{schema}/clusters/{clusterId}/timeline",
    methods = [HttpMethod.GET],
    summary = "Returns, per video, the segments in which this cluster's members appear (timestamped).",
    operationId = "getClusterTimeline",
    tags = ["Cluster"],
    pathParams = [
        OpenApiParam("schema", type = String::class, required = true),
        OpenApiParam("clusterId", type = String::class, required = true),
    ],
    responses = [
        OpenApiResponse("200", [OpenApiContent(ClusterTimelineResponse::class)]),
        OpenApiResponse("404", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun getClusterTimeline(ctx: Context, schema: Schema) {
    val clusterId = parseUuidOrThrow(ctx.pathParam("clusterId"), "clusterId")
    val reader = schema.connection.getRetrievableReader()

    if (reader.get(clusterId) == null) {
        throw ErrorStatusException(404, "Cluster $clusterId not found.")
    }
    /* Helper handles both cluster targets; downstream stays face-level. */
    val faceIds = detectionIdsOfCluster(schema, clusterId)
    if (faceIds.isEmpty()) {
        ctx.json(ClusterTimelineResponse(clusterId.toString(), emptyList()))
        return
    }

    /* face -> segment, then collapse to segment -> detection count. */
    val faceToSegment = parentMapOf(schema, faceIds)
    val detectionCountBySegment: Map<UUID, Int> = faceToSegment.values.groupingBy { it }.eachCount()

    /* Resolve source id + time range for every unique segment in one bulk pass. */
    val segmentIds = detectionCountBySegment.keys.toList()
    val info = segmentDisplayInfoFor(schema, segmentIds)

    /* Group by parent source, dropping segments that lack a source link or time descriptor —
       those can't be placed on a timeline. */
    data class Bucket(var filePath: String?, val segs: MutableList<ClusterTimelineSegment>)
    val bySource = mutableMapOf<UUID, Bucket>()
    for (segId in segmentIds) {
        val d = info[segId] ?: continue
        val src = d.sourceId ?: continue
        val s = d.startNs ?: continue
        val e = d.endNs ?: continue
        val bucket = bySource.getOrPut(src) { Bucket(d.filePath, mutableListOf()) }
        if (bucket.filePath == null) bucket.filePath = d.filePath
        bucket.segs += ClusterTimelineSegment(
            segmentId = segId.toString(),
            startNs = s,
            endNs = e,
            detectionCount = detectionCountBySegment[segId] ?: 0,
        )
    }

    val videos = bySource.map { (srcId, bucket) ->
        val sorted = bucket.segs.sortedBy { it.startNs }
        ClusterTimelineVideo(
            sourceId = srcId.toString(),
            filePath = bucket.filePath,
            lastAppearanceNs = sorted.maxOf { it.endNs },
            segments = sorted,
        )
    }.sortedByDescending { it.segments.size }

    ctx.json(ClusterTimelineResponse(clusterId.toString(), videos))
}

@OpenApi(
    path = "/api/{schema}/clusters/{clusterId}/co-occurrences",
    methods = [HttpMethod.GET],
    summary = "Returns clusters that share parent segments with the given cluster, ranked by shared count.",
    operationId = "getClusterCoOccurrences",
    tags = ["Cluster"],
    pathParams = [
        OpenApiParam("schema", type = String::class, required = true),
        OpenApiParam("clusterId", type = String::class, required = true),
    ],
    queryParams = [
        OpenApiParam("limit", type = Int::class),
        OpenApiParam("minShared", type = Int::class, description = "Drop partners with fewer than N shared segments (default: 1)"),
        OpenApiParam("target", type = String::class, description = "detections | tracks — limits partners to clusters of this kind. Defaults to the same kind as the queried cluster."),
    ],
    responses = [
        OpenApiResponse("200", [OpenApiContent(CoOccurrenceResponse::class)]),
        OpenApiResponse("404", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun getClusterCoOccurrences(ctx: Context, schema: Schema) {
    val clusterId = parseUuidOrThrow(ctx.pathParam("clusterId"), "clusterId")
    val limit = ctx.queryParam("limit")?.toIntOrNull()?.coerceAtLeast(1) ?: 50
    val minShared = ctx.queryParam("minShared")?.toIntOrNull() ?: 1

    val reader = schema.connection.getRetrievableReader()

    val ownMemberIds = memberIdsOf(schema, clusterId)
    if (ownMemberIds.isEmpty() && reader.get(clusterId) == null) {
        throw ErrorStatusException(404, "Cluster $clusterId not found.")
    }
    val ownMemberType = ownMemberIds.firstOrNull()?.let { reader.get(it)?.type } ?: "FACE_DETECTION"

    /* Default partner-target matches the queried cluster's own kind. Cross-kind co-occurrences
       (a track cluster paired with a detection cluster) would be semantically odd, so opt-in only. */
    val partnerTarget: String = ctx.queryParam("target")?.lowercase()?.let {
        when (it) {
            "detections", "detection" -> "FACE_DETECTION"
            "tracks", "track" -> "FACE_TRACK"
            else -> throw ErrorStatusException(400, "Invalid target '$it'; expected 'detections' or 'tracks'.")
        }
    } ?: ownMemberType

    /* Expand own cluster to face detections — handles both targets uniformly. */
    val myFaceIds = detectionIdsOfCluster(schema, clusterId)
    val mySegments: Set<UUID> = parentMapOf(schema, myFaceIds).values.toSet()
    if (mySegments.isEmpty()) {
        ctx.json(CoOccurrenceResponse(clusterId.toString(), 0, emptyList()))
        return
    }

    /* All face detections appearing in those segments, with their parent segments. */
    val faceParentMap = reader.getConnections(
        subjectIds = emptyList(),
        predicates = listOf("partOf"),
        objectIds = mySegments,
    ).associate { it.subjectId to it.objectId } // face -> segment

    /* Walk from face → cluster-membership. Path depends on partnerTarget:
       - detections: face → memberOfCluster (direct)
       - tracks:     face → partOfTrack → memberOfCluster (track's cluster) */
    val faceToCandidateClusters: List<Pair<UUID, UUID>> = if (partnerTarget == "FACE_DETECTION") {
        reader.getConnections(
            subjectIds = faceParentMap.keys,
            predicates = listOf("memberOfCluster"),
            objectIds = emptyList(),
        ).map { it.subjectId to it.objectId }.toList()
    } else {
        /* face → track */
        val faceToTrack = reader.getConnections(
            subjectIds = faceParentMap.keys,
            predicates = listOf("partOfTrack"),
            objectIds = emptyList(),
        ).map { it.subjectId to it.objectId }.toList()
        val trackIds = faceToTrack.map { it.second }.distinct()
        /* track → cluster, chunked. */
        val trackToCluster: Map<UUID, UUID> = trackIds.chunked(30_000).flatMap { batch ->
            reader.getConnections(
                subjectIds = batch,
                predicates = listOf("memberOfCluster"),
                objectIds = emptyList(),
            ).map { it.subjectId to it.objectId }.toList()
        }.toMap()
        faceToTrack.mapNotNull { (face, track) -> trackToCluster[track]?.let { face to it } }
    }

    val coClusterCounts = mutableMapOf<UUID, MutableSet<UUID>>() // otherCluster -> segments shared with `me`
    for ((face, other) in faceToCandidateClusters) {
        if (other == clusterId) continue
        val seg = faceParentMap[face] ?: continue
        coClusterCounts.getOrPut(other) { mutableSetOf() }.add(seg)
    }

    val labels = ClusterStore.forSchema(schema).allLabels()
    val ranked = coClusterCounts
        .map { (id, segs) -> id to segs.size }
        .filter { it.second >= minShared }
        .sortedByDescending { it.second }

    val partners = ranked.take(limit).map { (id, shared) ->
        CoOccurrenceItem(
            clusterId = id.toString(),
            label = labels[id],
            memberCount = memberIdsOf(schema, id).size,
            sharedSegments = shared,
        )
    }

    ctx.json(CoOccurrenceResponse(clusterId.toString(), ranked.size, partners))
}

@OpenApi(
    path = "/api/{schema}/clusters/{clusterId}/label",
    methods = [HttpMethod.PATCH],
    summary = "Sets, updates, or clears the curator label for a cluster.",
    operationId = "patchClusterLabel",
    tags = ["Cluster"],
    pathParams = [
        OpenApiParam("schema", type = String::class, required = true),
        OpenApiParam("clusterId", type = String::class, required = true),
    ],
    requestBody = OpenApiRequestBody([OpenApiContent(ClusterLabelPatch::class)]),
    responses = [
        OpenApiResponse("204"),
        OpenApiResponse("400", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("404", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun patchClusterLabel(ctx: Context, schema: Schema) {
    val clusterId = parseUuidOrThrow(ctx.pathParam("clusterId"), "clusterId")
    val body = try { ctx.bodyAsClass<ClusterLabelPatch>() }
        catch (e: Exception) { throw ErrorStatusException(400, "Invalid label patch body: ${e.message}") }

    if (schema.connection.getRetrievableReader().get(clusterId) == null) {
        throw ErrorStatusException(404, "Cluster $clusterId not found.")
    }
    ClusterStore.forSchema(schema).setLabel(clusterId, body.label?.trim()?.takeIf { it.isNotEmpty() })
    ctx.status(204)
}

@OpenApi(
    path = "/api/{schema}/clusters/merge",
    methods = [HttpMethod.POST],
    summary = "Merges multiple clusters into a new cluster. Source clusters become empty.",
    operationId = "mergeClusters",
    tags = ["Cluster"],
    pathParams = [OpenApiParam("schema", type = String::class, required = true)],
    requestBody = OpenApiRequestBody([OpenApiContent(ClusterMergeRequest::class)]),
    responses = [
        OpenApiResponse("200", [OpenApiContent(ClusterMutationResult::class)]),
        OpenApiResponse("400", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun mergeClusters(ctx: Context, schema: Schema) {
    val body = try { ctx.bodyAsClass<ClusterMergeRequest>() }
        catch (e: Exception) { throw ErrorStatusException(400, "Invalid merge body: ${e.message}") }

    val sourceIds = body.clusterIds.map { parseUuidOrThrow(it, "clusterId") }
    if (sourceIds.size < 2) throw ErrorStatusException(400, "Need at least two clusters to merge.")

    val reader = schema.connection.getRetrievableReader()
    val writer = schema.connection.getRetrievableWriter()
    val state = ClusterStore.forSchema(schema)

    /* Inherit label if not explicitly given. */
    val labels = state.allLabels()
    val effectiveLabel = body.label?.trim()?.takeIf { it.isNotEmpty() }
        ?: sourceIds.firstNotNullOfOrNull { labels[it] }

    /* Collect members + exemplars across all sources. */
    val allMembers = sourceIds.flatMap { memberIdsOf(schema, it) }.distinct()
    val allExemplars = sourceIds.flatMap { exemplarIdsOf(schema, it) }.distinct()

    val newClusterId = UUID.randomUUID()
    writer.add(Ingested(newClusterId, "FACE_CLUSTER", transient = false))

    /* Rewire memberOfCluster + exemplarOfCluster from sources to new cluster. */
    for (src in sourceIds) {
        val memRels = memberIdsOf(schema, src).map {
            Relationship.ById(it, "memberOfCluster", src, transient = false)
        }
        if (memRels.isNotEmpty()) writer.disconnectAll(memRels)

        val exRels = exemplarIdsOf(schema, src).map {
            Relationship.ById(it, "exemplarOfCluster", src, transient = false)
        }
        if (exRels.isNotEmpty()) writer.disconnectAll(exRels)
    }
    writer.connectAll(allMembers.map {
        Relationship.ById(it, "memberOfCluster", newClusterId, transient = false)
    })
    writer.connectAll(allExemplars.map {
        Relationship.ById(it, "exemplarOfCluster", newClusterId, transient = false)
    })

    /* Recompute centroid from previous centroids when possible. */
    val sourceCentroids = sourceIds.mapNotNull { id -> state.getCentroid(id)?.let { id to it } }
    if (sourceCentroids.isNotEmpty()) {
        val mean = FaceClusteringService.computeMeanNormalized(sourceCentroids.map { it.second })
        state.setCentroid(newClusterId, mean)
    }
    for (src in sourceIds) state.remove(src)
    if (effectiveLabel != null) state.setLabel(newClusterId, effectiveLabel)

    ctx.json(ClusterMutationResult(
        newClusterId = newClusterId.toString(),
        movedMembers = allMembers.size,
        message = "Merged ${sourceIds.size} clusters.",
    ))
}

@OpenApi(
    path = "/api/{schema}/clusters/{clusterId}/split",
    methods = [HttpMethod.POST],
    summary = "Moves the given FACE_DETECTION ids out of a cluster into a new one.",
    operationId = "splitCluster",
    tags = ["Cluster"],
    pathParams = [
        OpenApiParam("schema", type = String::class, required = true),
        OpenApiParam("clusterId", type = String::class, required = true),
    ],
    requestBody = OpenApiRequestBody([OpenApiContent(ClusterSplitRequest::class)]),
    responses = [
        OpenApiResponse("200", [OpenApiContent(ClusterMutationResult::class)]),
        OpenApiResponse("400", [OpenApiContent(ErrorStatus::class)]),
        OpenApiResponse("404", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun splitCluster(ctx: Context, schema: Schema) {
    val srcClusterId = parseUuidOrThrow(ctx.pathParam("clusterId"), "clusterId")
    val body = try { ctx.bodyAsClass<ClusterSplitRequest>() }
        catch (e: Exception) { throw ErrorStatusException(400, "Invalid split body: ${e.message}") }
    if (body.faceIds.isEmpty()) throw ErrorStatusException(400, "faceIds must not be empty.")

    val faceIds = body.faceIds.map { parseUuidOrThrow(it, "faceId") }
    val reader = schema.connection.getRetrievableReader()
    val writer = schema.connection.getRetrievableWriter()
    val state = ClusterStore.forSchema(schema)

    val srcMembers = memberIdsOf(schema, srcClusterId).toSet()
    if (srcMembers.isEmpty() && reader.get(srcClusterId) == null) {
        throw ErrorStatusException(404, "Cluster $srcClusterId not found.")
    }
    val toMove = faceIds.filter { it in srcMembers }
    if (toMove.isEmpty()) throw ErrorStatusException(400, "None of the supplied faceIds belong to cluster $srcClusterId.")

    val newClusterId = UUID.randomUUID()
    writer.add(Ingested(newClusterId, "FACE_CLUSTER", transient = false))

    writer.disconnectAll(toMove.map { Relationship.ById(it, "memberOfCluster", srcClusterId, transient = false) })
    writer.connectAll(toMove.map { Relationship.ById(it, "memberOfCluster", newClusterId, transient = false) })

    /* Recompute centroids of both clusters from current members. */
    @Suppress("UNCHECKED_CAST")
    val embField = schema["face"] as? Schema.Field<*, FloatVectorDescriptor>
    if (embField != null) {
        val embReader = embField.getReader()

        fun recomputeCentroid(clusterId: UUID) {
            val members = memberIdsOf(schema, clusterId)
            if (members.isEmpty()) { state.remove(clusterId); return }
            val vectors = embReader.getAllForRetrievable(members)
                .mapNotNull { it.retrievableId?.let { _ -> it.vector.value } }
                .toList()
            if (vectors.isEmpty()) return
            state.setCentroid(clusterId, FaceClusteringService.computeMeanNormalized(vectors))
        }
        recomputeCentroid(srcClusterId)
        recomputeCentroid(newClusterId)
    }
    body.newLabel?.trim()?.takeIf { it.isNotEmpty() }?.let { state.setLabel(newClusterId, it) }

    ctx.json(ClusterMutationResult(
        newClusterId = newClusterId.toString(),
        movedMembers = toMove.size,
        message = "Split $srcClusterId — ${toMove.size} members moved.",
    ))
}

@OpenApi(
    path = "/api/{schema}/clusters/stats/group-sizes",
    methods = [HttpMethod.GET],
    summary = "Histogram of how many distinct clusters appear together per segment.",
    operationId = "getGroupSizeHistogram",
    tags = ["Cluster"],
    pathParams = [OpenApiParam("schema", type = String::class, required = true)],
    queryParams = [
        OpenApiParam("target", type = String::class, description = "detections | tracks (default: detections) — restricts the histogram to one kind of cluster."),
    ],
    responses = [OpenApiResponse("200", [OpenApiContent(GroupSizeHistogramResponse::class)])]
)
fun getGroupSizeHistogram(ctx: Context, schema: Schema) {
    val reader = schema.connection.getRetrievableReader()
    val targetType = when (ctx.queryParam("target")?.lowercase()) {
        "tracks", "track" -> "FACE_TRACK"
        "detections", "detection", null, "" -> "FACE_DETECTION"
        else -> throw ErrorStatusException(400, "Invalid target '${ctx.queryParam("target")}'; expected 'detections' or 'tracks'.")
    }

    /* face -> cluster.
       - detections: direct face → memberOfCluster
       - tracks:     face → partOfTrack → memberOfCluster (the track's cluster) */
    val faceToCluster: Map<UUID, UUID> = if (targetType == "FACE_DETECTION") {
        reader.getConnections(
            subjectIds = emptyList(),
            predicates = listOf("memberOfCluster"),
            objectIds = emptyList(),
        ).associate { it.subjectId to it.objectId }
    } else {
        val faceToTrack = reader.getConnections(
            subjectIds = emptyList(),
            predicates = listOf("partOfTrack"),
            objectIds = emptyList(),
        ).map { it.subjectId to it.objectId }.toList()
        val trackIds = faceToTrack.map { it.second }.distinct()
        val trackToCluster: Map<UUID, UUID> = trackIds.chunked(30_000).flatMap { batch ->
            reader.getConnections(
                subjectIds = batch,
                predicates = listOf("memberOfCluster"),
                objectIds = emptyList(),
            ).map { it.subjectId to it.objectId }.toList()
        }.toMap()
        faceToTrack.mapNotNull { (face, track) -> trackToCluster[track]?.let { face to it } }.toMap()
    }

    /* face -> parent segment; chunk to stay under the 65k param cap. */
    val partOfPairs: List<Pair<UUID, UUID>> = faceToCluster.keys.chunked(30_000).flatMap { batch ->
        reader.getConnections(
            subjectIds = batch,
            predicates = listOf("partOf"),
            objectIds = emptyList(),
        ).map { it.subjectId to it.objectId }.toList()
    }

    /* segment -> distinct cluster ids present. */
    val segmentToClusters = mutableMapOf<UUID, MutableSet<UUID>>()
    for ((faceId, segmentId) in partOfPairs) {
        val clusterId = faceToCluster[faceId] ?: continue
        segmentToClusters.computeIfAbsent(segmentId) { mutableSetOf() }.add(clusterId)
    }

    val histogram = segmentToClusters.values
        .groupingBy { it.size }
        .eachCount()
        .toSortedMap()

    val bins = histogram.map { (k, count) -> GroupSizeBin(k = k, segmentCount = count) }
    val total = segmentToClusters.size

    ctx.json(GroupSizeHistogramResponse(totalSegments = total, bins = bins))
}

@OpenApi(
    path = "/api/{schema}/clusters/identify",
    methods = [HttpMethod.POST],
    summary = "Returns clusters whose centroids most resemble the supplied face embedding.",
    operationId = "identifyCluster",
    tags = ["Cluster"],
    pathParams = [OpenApiParam("schema", type = String::class, required = true)],
    queryParams = [OpenApiParam("target", type = String::class, description = "detections | tracks | all (default: all) — restrict scoring to one cluster kind.")],
    requestBody = OpenApiRequestBody([OpenApiContent(ClusterIdentifyRequest::class)], required = true),
    responses = [
        OpenApiResponse("200", [OpenApiContent(ClusterIdentifyResponse::class)]),
        OpenApiResponse("400", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun identifyCluster(ctx: Context, schema: Schema) {
    val body = runCatching { ctx.bodyAsClass<ClusterIdentifyRequest>() }
        .getOrElse { throw ErrorStatusException(400, "Invalid request body.") }

    if (body.embedding.isEmpty()) throw ErrorStatusException(400, "embedding must not be empty.")
    if (body.topK < 1) throw ErrorStatusException(400, "topK must be >= 1.")
    val targetType = parseTargetParam(ctx.queryParam("target"))

    /* Renormalize defensively — centroids are stored normalized; cosine = dot product when both
       are unit-length, otherwise the threshold has unclear semantics. */
    val q = FloatArray(body.embedding.size) { body.embedding[it] }
    val qNorm = run {
        var s = 0.0
        for (v in q) s += (v * v).toDouble()
        kotlin.math.sqrt(s).toFloat()
    }
    if (qNorm <= 0f || !qNorm.isFinite()) {
        throw ErrorStatusException(400, "embedding has zero or non-finite norm.")
    }
    for (i in q.indices) q[i] /= qNorm

    val store = ClusterStore.forSchema(schema)
    val labels = store.allLabels()
    val centroids = store.allCentroids().let { all ->
        if (targetType == null) all
        else clusterIdsByTarget(schema, targetType).let { allowed -> all.filterKeys { it in allowed } }
    }

    if (centroids.isEmpty()) {
        ctx.json(ClusterIdentifyResponse(totalClusters = 0, matches = emptyList()))
        return
    }

    val scored = centroids.mapNotNull { (clusterId, centroid) ->
        if (centroid.size != q.size) return@mapNotNull null // skip dim mismatch
        var dot = 0f
        for (i in q.indices) dot += q[i] * centroid[i]
        clusterId to dot
    }

    val matches = scored.asSequence()
        .filter { it.second >= body.threshold }
        .sortedByDescending { it.second }
        .take(body.topK)
        .map { (id, sim) ->
            ClusterIdentifyMatch(
                clusterId = id.toString(),
                similarity = sim,
                label = labels[id],
            )
        }
        .toList()

    ctx.json(ClusterIdentifyResponse(totalClusters = centroids.size, matches = matches))
}

@OpenApi(
    path = "/api/{schema}/clusters/identify-batch",
    methods = [HttpMethod.POST],
    summary = "For every cluster, returns its best-matching candidate face above the threshold.",
    operationId = "identifyClusterBatch",
    tags = ["Cluster"],
    pathParams = [OpenApiParam("schema", type = String::class, required = true)],
    queryParams = [OpenApiParam("target", type = String::class, description = "detections | tracks | all (default: all) — restrict scoring to one cluster kind.")],
    requestBody = OpenApiRequestBody([OpenApiContent(ClusterIdentifyBatchRequest::class)], required = true),
    responses = [
        OpenApiResponse("200", [OpenApiContent(ClusterIdentifyBatchResponse::class)]),
        OpenApiResponse("400", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun identifyClusterBatch(ctx: Context, schema: Schema) {
    val body = runCatching { ctx.bodyAsClass<ClusterIdentifyBatchRequest>() }
        .getOrElse { throw ErrorStatusException(400, "Invalid request body.") }

    if (body.candidates.isEmpty()) {
        throw ErrorStatusException(400, "candidates must not be empty.")
    }
    val targetType = parseTargetParam(ctx.queryParam("target"))

    /* Normalize each candidate embedding once up front (cosine = dot when both unit-length). */
    val candidates: List<Pair<String, FloatArray>> = body.candidates.map { c ->
        if (c.name.isBlank()) throw ErrorStatusException(400, "candidate name must not be blank.")
        if (c.embedding.isEmpty()) throw ErrorStatusException(400, "candidate '${c.name}' has empty embedding.")
        val v = FloatArray(c.embedding.size) { c.embedding[it] }
        var s = 0.0
        for (x in v) s += (x * x).toDouble()
        val n = kotlin.math.sqrt(s).toFloat()
        if (n <= 0f || !n.isFinite()) {
            throw ErrorStatusException(400, "candidate '${c.name}' has zero or non-finite norm.")
        }
        for (i in v.indices) v[i] /= n
        c.name to v
    }

    val store = ClusterStore.forSchema(schema)
    val labels = store.allLabels()
    val centroids = store.allCentroids().let { all ->
        if (targetType == null) all
        else clusterIdsByTarget(schema, targetType).let { allowed -> all.filterKeys { it in allowed } }
    }

    val assignments = mutableListOf<ClusterIdentifyAssignment>()
    val unmatched = mutableListOf<UnmatchedClusterRow>()

    for ((clusterId, centroid) in centroids) {
        /* For each cluster: cosine vs every candidate, keep the winner above threshold. */
        var bestName: String? = null
        var bestSim = Float.NEGATIVE_INFINITY
        for ((name, embedding) in candidates) {
            if (embedding.size != centroid.size) continue
            var dot = 0f
            for (i in embedding.indices) dot += embedding[i] * centroid[i]
            if (dot > bestSim) {
                bestSim = dot
                bestName = name
            }
        }
        if (bestName != null && bestSim >= body.threshold) {
            assignments += ClusterIdentifyAssignment(
                clusterId = clusterId.toString(),
                bestName = bestName,
                similarity = bestSim,
                existingLabel = labels[clusterId],
            )
        } else {
            unmatched += UnmatchedClusterRow(
                clusterId = clusterId.toString(),
                label = labels[clusterId],
            )
        }
    }

    assignments.sortByDescending { it.similarity }
    /* Stable but human-friendly ordering for the unmatched list: labelled first, then by id. */
    unmatched.sortWith(compareBy({ it.label.isNullOrBlank() }, { it.label ?: "" }, { it.clusterId }))

    ctx.json(ClusterIdentifyBatchResponse(
        totalClusters = centroids.size,
        assignments = assignments,
        unmatched = unmatched,
    ))
}

@OpenApi(
    path = "/api/{schema}/clusters/{clusterId}",
    methods = [HttpMethod.DELETE],
    summary = "Deletes a cluster: detaches its members/exemplars, drops the retrievable, and removes its centroid/label.",
    operationId = "deleteCluster",
    tags = ["Cluster"],
    pathParams = [
        OpenApiParam("schema", type = String::class, required = true),
        OpenApiParam("clusterId", type = String::class, required = true),
    ],
    responses = [
        OpenApiResponse("204"),
        OpenApiResponse("404", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun deleteCluster(ctx: Context, schema: Schema) {
    val clusterId = parseUuidOrThrow(ctx.pathParam("clusterId"), "clusterId")
    val reader = schema.connection.getRetrievableReader()
    val writer = schema.connection.getRetrievableWriter()

    val clusterRetrievable = reader.get(clusterId)
        ?: throw ErrorStatusException(404, "Cluster $clusterId not found.")

    /* Detach all member + exemplar edges so the underlying FACE_DETECTIONs are released back to
       the 'unclustered' pool. The detections themselves are valuable independently and stay. */
    val memberIds = memberIdsOf(schema, clusterId)
    if (memberIds.isNotEmpty()) {
        writer.disconnectAll(memberIds.map { Relationship.ById(it, "memberOfCluster", clusterId, transient = false) })
    }
    val exemplarIds = exemplarIdsOf(schema, clusterId)
    if (exemplarIds.isNotEmpty()) {
        writer.disconnectAll(exemplarIds.map { Relationship.ById(it, "exemplarOfCluster", clusterId, transient = false) })
    }

    /* Drop the FACE_CLUSTER retrievable itself. */
    writer.delete(clusterRetrievable)

    /* Drop the persisted centroid + label so it doesn't reappear in identify/listing. */
    ClusterStore.forSchema(schema).remove(clusterId)

    logger.info { "[Cluster] Deleted cluster $clusterId (released ${memberIds.size} members, ${exemplarIds.size} exemplars)." }
    ctx.status(204)
}

@OpenApi(
    path = "/api/{schema}/clusters/runs/{runId}",
    methods = [HttpMethod.DELETE],
    summary = "Deletes a FACE_CLUSTER_RUN and every FACE_CLUSTER it produced. " +
              "Member retrievables (FACE_DETECTION or FACE_TRACK) are kept; only their cluster memberships are dropped.",
    operationId = "deleteClusterRun",
    tags = ["Cluster"],
    pathParams = [
        OpenApiParam("schema", type = String::class, required = true),
        OpenApiParam("runId", type = String::class, required = true),
    ],
    responses = [
        OpenApiResponse("204"),
        OpenApiResponse("404", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun deleteClusterRun(ctx: Context, schema: Schema) {
    val runId = parseUuidOrThrow(ctx.pathParam("runId"), "runId")
    val reader = schema.connection.getRetrievableReader()
    val writer = schema.connection.getRetrievableWriter()
    val runRetrievable = reader.get(runId)
        ?: throw ErrorStatusException(404, "Cluster run $runId not found.")

    /* Find every FACE_CLUSTER produced by this run and run the same detach-then-delete sequence
       deleteCluster does for each. Member retrievables (detections/tracks) themselves are preserved. */
    val clusterIds: List<UUID> = reader.getConnections(
        subjectIds = emptyList(),
        predicates = listOf("producedByRun"),
        objectIds = listOf(runId),
    ).map { it.subjectId }.toList()

    val store = ClusterStore.forSchema(schema)
    var detachedMembers = 0
    var detachedExemplars = 0
    for (cid in clusterIds) {
        val cluster = reader.get(cid) ?: continue
        val memberIds = memberIdsOf(schema, cid)
        if (memberIds.isNotEmpty()) {
            writer.disconnectAll(memberIds.map { Relationship.ById(it, "memberOfCluster", cid, transient = false) })
            detachedMembers += memberIds.size
        }
        val exemplarIds = exemplarIdsOf(schema, cid)
        if (exemplarIds.isNotEmpty()) {
            writer.disconnectAll(exemplarIds.map { Relationship.ById(it, "exemplarOfCluster", cid, transient = false) })
            detachedExemplars += exemplarIds.size
        }
        writer.disconnectAll(listOf(Relationship.ById(cid, "producedByRun", runId, transient = false)))
        writer.delete(cluster)
        store.remove(cid)
    }

    writer.delete(runRetrievable)
    logger.info {
        "[Cluster] Deleted run $runId (${clusterIds.size} clusters, $detachedMembers members, $detachedExemplars exemplars detached)."
    }
    ctx.status(204)
}

@OpenApi(
    path = "/api/{schema}/clusters/match",
    methods = [HttpMethod.POST],
    summary = "Server-side AND-intersection of cluster memberships, with optional spatial ordering.",
    operationId = "matchClusters",
    tags = ["Cluster"],
    pathParams = [OpenApiParam("schema", type = String::class, required = true)],
    requestBody = OpenApiRequestBody([OpenApiContent(ClusterMatchRequest::class)], required = true),
    responses = [
        OpenApiResponse("200", [OpenApiContent(ClusterMatchResponse::class)]),
        OpenApiResponse("400", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun matchClusters(ctx: Context, schema: Schema) {
    val body = runCatching { ctx.bodyAsClass<ClusterMatchRequest>() }
        .getOrElse { throw ErrorStatusException(400, "Invalid request body.") }

    val include = body.include.map { parseUuidOrThrow(it, "include cluster") }.toSet()
    val exclude = body.exclude.map { parseUuidOrThrow(it, "exclude cluster") }.toSet()
    val spatialOrder = body.spatialOrder?.map { parseUuidOrThrow(it, "spatialOrder cluster") }
    val axis = body.axis.lowercase().also {
        require(it == "x" || it == "y") { "axis must be 'x' or 'y'." }
    }
    val limit = body.limit.coerceAtLeast(1)

    if (include.isEmpty() && spatialOrder.isNullOrEmpty()) {
        throw ErrorStatusException(400, "Must specify at least one include or spatialOrder cluster.")
    }

    val reader = schema.connection.getRetrievableReader()

    /* All clusters we care about (membership + spatial). */
    val allClusters = include + exclude + (spatialOrder?.toSet() ?: emptySet())

    /* face -> cluster, but only for faces in the requested clusters. Uses detectionIdsOfCluster
       so both detection-clusters and track-clusters resolve to face ids uniformly. A face that's
       transitively in multiple requested clusters keeps the *first* one we see — same behavior
       as the previous single-Map associate. */
    val faceToCluster: MutableMap<UUID, UUID> = mutableMapOf()
    for (cid in allClusters) {
        for (face in detectionIdsOfCluster(schema, cid)) {
            faceToCluster.putIfAbsent(face, cid)
        }
    }

    /* face -> parent segment. */
    val partOfPairs: List<Pair<UUID, UUID>> = faceToCluster.keys.chunked(30_000).flatMap { batch ->
        reader.getConnections(
            subjectIds = batch,
            predicates = listOf("partOf"),
            objectIds = emptyList(),
        ).map { it.subjectId to it.objectId }.toList()
    }

    /* segment -> map<cluster, list<face>>. */
    val segmentClusterFaces = mutableMapOf<UUID, MutableMap<UUID, MutableList<UUID>>>()
    for ((faceId, segmentId) in partOfPairs) {
        val clusterId = faceToCluster[faceId] ?: continue
        segmentClusterFaces
            .computeIfAbsent(segmentId) { mutableMapOf() }
            .computeIfAbsent(clusterId) { mutableListOf() }
            .add(faceId)
    }

    /* Apply include/exclude filter. */
    val survivingSegments = segmentClusterFaces.filter { (_, presentByCluster) ->
        val present = presentByCluster.keys
        include.all { it in present } && exclude.none { it in present }
    }

    /* If no spatial constraint, score = 1.0 for each survivor, sort by segment id (stable). */
    if (spatialOrder.isNullOrEmpty()) {
        val pageIds = survivingSegments.keys.sortedBy { it.toString() }.take(limit)
        val info = segmentDisplayInfoFor(schema, pageIds)
        val hits = pageIds.map { segId ->
            val d = info[segId]
            ClusterMatchHit(
                segmentId = segId.toString(),
                score = 1.0f,
                sourceId = d?.sourceId?.toString(),
                filePath = d?.filePath,
                startNs = d?.startNs,
                endNs = d?.endNs,
            )
        }
        ctx.json(ClusterMatchResponse(total = survivingSegments.size, limit = limit, results = hits))
        return
    }

    /* From here on, spatialOrder is guaranteed non-null and non-empty.
       Bind to a non-null local so smart-cast doesn't get lost in inner lambdas. */
    val order: List<UUID> = spatialOrder

    /* Spatial constraint: need bboxes for every face that's in a spatialOrder cluster in a surviving segment. */
    @Suppress("UNCHECKED_CAST")
    val bboxField = schema["facebbox"] as? Schema.Field<*, FloatVectorDescriptor>
        ?: throw ErrorStatusException(500, "Schema has no 'facebbox' field configured; cannot run spatial query.")

    val spatialSet = order.toSet()
    val facesNeeded: List<UUID> = survivingSegments.values
        .flatMap { byCluster -> byCluster.filterKeys { it in spatialSet }.values.flatten() }
        .distinct()

    val bboxByFace: Map<UUID, FloatArray> = facesNeeded.chunked(30_000).flatMap { batch ->
        bboxField.getReader().getAllForRetrievable(batch).toList()
    }.mapNotNull { d -> d.retrievableId?.let { it to d.vector.value } }.toMap()

    /* For each surviving segment, compute mean center along axis for each spatialOrder cluster. */
    val coordIdx = if (axis == "x") intArrayOf(0, 2) else intArrayOf(1, 3) // [x1,x2] or [y1,y2]

    data class Scored(val segmentId: UUID, val score: Float)
    val scored = mutableListOf<Scored>()
    for ((segmentId, byCluster) in survivingSegments) {
        /* For each cluster in spatialOrder, mean center across this segment's faces. */
        val centers = FloatArray(order.size)
        var allPresent = true
        for ((i, cid) in order.withIndex()) {
            val facesOfCluster = byCluster[cid].orEmpty()
            val xs = facesOfCluster.mapNotNull { bboxByFace[it] }.map { b ->
                ((b[coordIdx[0]] + b[coordIdx[1]]) / 2f)
            }
            if (xs.isEmpty()) { allPresent = false; break }
            centers[i] = xs.average().toFloat()
        }
        if (!allPresent) continue

        /* Check monotonic ascending and accumulate margin. */
        var totalMargin = 0f
        var monotonic = true
        for (i in 1 until centers.size) {
            val margin = centers[i] - centers[i - 1]
            if (margin <= 0f) { monotonic = false; break }
            totalMargin += margin
        }
        if (!monotonic) continue
        val score = totalMargin / (centers.size - 1).coerceAtLeast(1)
        scored.add(Scored(segmentId, score))
    }

    val total = scored.size
    val topScored = scored.sortedByDescending { it.score }.take(limit)
    val info = segmentDisplayInfoFor(schema, topScored.map { it.segmentId })
    val hits = topScored.map {
        val d = info[it.segmentId]
        ClusterMatchHit(
            segmentId = it.segmentId.toString(),
            score = it.score,
            sourceId = d?.sourceId?.toString(),
            filePath = d?.filePath,
            startNs = d?.startNs,
            endNs = d?.endNs,
        )
    }
    ctx.json(ClusterMatchResponse(total = total, limit = limit, results = hits))
}
