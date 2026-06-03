package org.vitrivr.engine.server.api.rest.handlers

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.http.Context
import io.javalin.http.bodyAsClass
import io.javalin.openapi.*
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
import org.vitrivr.engine.server.services.ClusterStateStore
import org.vitrivr.engine.server.services.ClusteringParams
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
        ClusterRunSummary(
            runId = run.id.toString(),
            algorithm = "HDBSCAN",
            embeddingField = "face",
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
    ctx.json(runs)
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
    val embeddingFieldName = ctx.queryParam("embeddingField") ?: "face"
    val resolvedHost = ctx.queryParam("pythonServer")
        ?: schema[embeddingFieldName]?.parameters?.get(HOST_PARAMETER_NAME)
        ?: HOST_PARAMETER_DEFAULT

    val params = ClusteringParams(
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

    val labels = ClusterStateStore.forSchema(schema.name).allLabels()

    val items = clusterIds.mapNotNull { clusterId ->
        val memberIds = memberIdsOf(schema, clusterId)
        if (memberIds.size < minMembers) return@mapNotNull null

        val label = labels[clusterId]
        if (onlyLabelled && label == null) return@mapNotNull null
        if (onlyUnlabelled && label != null) return@mapNotNull null

        val exemplarIds = exemplarIdsOf(schema, clusterId).ifEmpty { memberIds.take(3) }
        val parents = parentMapOf(schema, memberIds)
        val exemplars = exemplarIds.map {
            ClusterExemplar(faceId = it.toString(), parentId = parents[it]?.toString())
        }
        val segmentCount = parents.values.toSet().size

        ClusterGalleryItem(
            clusterId = clusterId.toString(),
            memberCount = memberIds.size,
            exemplars = exemplars,
            label = label,
            segmentCount = segmentCount,
        )
    }

    val sorted = when (sort) {
        "segments" -> items.sortedByDescending { it.segmentCount }
        "label" -> items.sortedWith(compareBy({ it.label.isNullOrBlank() }, { it.label ?: "" }))
        else -> items.sortedByDescending { it.memberCount }
    }

    val total = sorted.size
    val page = sorted.drop(offset).take(limit)

    ctx.json(
        ClusterGalleryResponse(
            runId = runIdFilter?.toString() ?: "all",
            schema = schema.name,
            totalClusters = total,
            limit = limit,
            offset = offset,
            clusters = page,
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

    val memberIds = memberIdsOf(schema, clusterId)
    if (memberIds.isEmpty() && schema.connection.getRetrievableReader().get(clusterId) == null) {
        throw ErrorStatusException(404, "Cluster $clusterId not found.")
    }

    val parents = parentMapOf(schema, memberIds)
    val page = memberIds.drop(offset).take(limit).map { faceId ->
        ClusterMemberItem(faceId = faceId.toString(), parentId = parents[faceId]?.toString())
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
    val centroid = ClusterStateStore.forSchema(schema.name).getCentroid(clusterId)
        ?: throw ErrorStatusException(404, "No centroid stored for cluster $clusterId.")
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

    val memberIds = memberIdsOf(schema, clusterId)
    if (memberIds.isEmpty() && schema.connection.getRetrievableReader().get(clusterId) == null) {
        throw ErrorStatusException(404, "Cluster $clusterId not found.")
    }

    val parents = parentMapOf(schema, memberIds)
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

    val myMembers = memberIdsOf(schema, clusterId)
    if (myMembers.isEmpty() && reader.get(clusterId) == null) {
        throw ErrorStatusException(404, "Cluster $clusterId not found.")
    }
    val mySegments: Set<UUID> = parentMapOf(schema, myMembers).values.toSet()
    if (mySegments.isEmpty()) {
        ctx.json(CoOccurrenceResponse(clusterId.toString(), 0, emptyList()))
        return
    }

    /* All FACE_DETECTION members in those segments, mapped back to their cluster (if any). */
    val faceParentMap = reader.getConnections(
        subjectIds = emptyList(),
        predicates = listOf("partOf"),
        objectIds = mySegments,
    ).associate { it.subjectId to it.objectId } // face -> segment

    val coClusterCounts = mutableMapOf<UUID, MutableSet<UUID>>() // otherCluster -> segments shared with `me`
    reader.getConnections(
        subjectIds = faceParentMap.keys,
        predicates = listOf("memberOfCluster"),
        objectIds = emptyList(),
    ).forEach { rel ->
        val other = rel.objectId
        if (other == clusterId) return@forEach
        val seg = faceParentMap[rel.subjectId] ?: return@forEach
        coClusterCounts.getOrPut(other) { mutableSetOf() }.add(seg)
    }

    val labels = ClusterStateStore.forSchema(schema.name).allLabels()
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
    ClusterStateStore.forSchema(schema.name).setLabel(clusterId, body.label?.trim()?.takeIf { it.isNotEmpty() })
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
    val state = ClusterStateStore.forSchema(schema.name)

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
    val state = ClusterStateStore.forSchema(schema.name)

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
