package org.vitrivr.engine.server.api.rest.handlers

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.http.Context
import io.javalin.openapi.*
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.module.features.feature.external.ExternalAnalyser.Companion.HOST_PARAMETER_DEFAULT
import org.vitrivr.engine.module.features.feature.external.ExternalAnalyser.Companion.HOST_PARAMETER_NAME
import org.vitrivr.engine.server.api.rest.model.ErrorStatus
import org.vitrivr.engine.server.api.rest.model.ErrorStatusException
import org.vitrivr.engine.server.api.rest.model.cluster.*
import org.vitrivr.engine.server.services.ClusteringParams
import org.vitrivr.engine.server.services.FaceClusteringService
import java.util.*

private val logger: KLogger = KotlinLogging.logger {}

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
        /**
         * TODO: Add clusterRunMeta descriptors.
         * For now return minimal info derivable from the retrievable ID alone.
         */
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
        OpenApiParam("embeddingField", type = String::class, description = "Schema field holding face embeddings (default: face)"),
        OpenApiParam("minClusterSize", type = Int::class, description = "HDBSCAN min_cluster_size (default: 5)"),
        OpenApiParam("minSamples", type = Int::class, description = "HDBSCAN min_samples (default: 3)"),
        OpenApiParam("pythonServer", type = String::class, description = "Python descriptor server URL (defaults to the 'host' parameter of the embedding field in the schema config)."),
    ],
    responses = [
        OpenApiResponse("200", [OpenApiContent(ClusterRunSummary::class)]),
        OpenApiResponse("500", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun triggerClustering(ctx: Context, schema: Schema) {
    val embeddingFieldName = ctx.queryParam("embeddingField") ?: "face"

    /* Either specify python server or use config-schema default */
    val resolvedHost = ctx.queryParam("pythonServer")
        ?: schema[embeddingFieldName]?.parameters?.get(HOST_PARAMETER_NAME)
        ?: HOST_PARAMETER_DEFAULT

    val params = ClusteringParams(
        embeddingFieldName = embeddingFieldName,
        minClusterSize = ctx.queryParam("minClusterSize")?.toIntOrNull() ?: 5,
        minSamples = ctx.queryParam("minSamples")?.toIntOrNull() ?: 3,
        pythonServerUrl = resolvedHost,
    )

    logger.info { "Clustering run triggered for schema '${schema.name}' with params=$params" }

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
        OpenApiParam("runId", type = String::class, description = "Filter to a specific run UUID"),
        OpenApiParam("limit", type = Int::class, description = "Page size (default: 50)"),
        OpenApiParam("offset", type = Int::class, description = "Page offset (default: 0)"),
        OpenApiParam("minMembers", type = Int::class, description = "Minimum member count to include a cluster"),
    ],
    responses = [OpenApiResponse("200", [OpenApiContent(ClusterGalleryResponse::class)])]
)
fun listClusters(ctx: Context, schema: Schema) {
    val runIdFilter = ctx.queryParam("runId")?.let { runUuid ->
        runCatching { UUID.fromString(runUuid) }.getOrNull()
    }
    val limit = ctx.queryParam("limit")?.toIntOrNull()?.coerceAtLeast(1) ?: 50
    val offset = ctx.queryParam("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val minMembers = ctx.queryParam("minMembers")?.toIntOrNull() ?: 1

    val reader = schema.connection.getRetrievableReader()
    val exemplarCount = 3

    // Determine which FACE_CLUSTER retrievables belong to the requested run
    val clusterIds: List<UUID> = if (runIdFilter != null) {
        // producedByRun relationships: subject = FACE_CLUSTER, object = FACE_CLUSTER_RUN
        reader.getConnections(
            subjectIds = emptyList(),
            predicates = listOf("producedByRun"),
            objectIds = listOf(runIdFilter),
        ).map { it.subjectId }.toList()
    } else {
        reader.getAll("FACE_CLUSTER").map { it.id }.toList()
    }

    /* For each cluster load member count and exemplar IDs */
    val items = clusterIds.mapNotNull { clusterId ->
        val memberIds = reader.getConnections(
            subjectIds = emptyList(),
            predicates = listOf("memberOfCluster"),
            objectIds = listOf(clusterId),
        ).map { it.subjectId }.toList()

        if (memberIds.size < minMembers) return@mapNotNull null

        ClusterGalleryItem(
            clusterId = clusterId.toString(),
            memberCount = memberIds.size,
            exemplarFaceIds = memberIds.take(exemplarCount).map { it.toString() },
        )
    }

    val total = items.size
    val page = items.drop(offset).take(limit)

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
    queryParams = [
        OpenApiParam("limit", type = Int::class),
        OpenApiParam("offset", type = Int::class),
    ],
    responses = [
        OpenApiResponse("200", [OpenApiContent(ClusterMemberPage::class)]),
        OpenApiResponse("404", [OpenApiContent(ErrorStatus::class)]),
    ]
)
fun getClusterMembers(ctx: Context, schema: Schema) {
    val clusterId = runCatching { UUID.fromString(ctx.pathParam("clusterId")) }.getOrElse {
        throw ErrorStatusException(400, "Invalid clusterId format.")
    }
    val limit = ctx.queryParam("limit")?.toIntOrNull()?.coerceAtLeast(1) ?: 100
    val offset = ctx.queryParam("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    val reader = schema.connection.getRetrievableReader()

    /* Fetch all memberOfCluster relationships pointing to this cluster */
    val memberIds = reader.getConnections(
        subjectIds = emptyList(),
        predicates = listOf("memberOfCluster"),
        objectIds = listOf(clusterId),
    ).map { it.subjectId }.toList()

    if (memberIds.isEmpty() && reader.get(clusterId) == null) {
        throw ErrorStatusException(404, "Cluster $clusterId not found.")
    }

    /* Resolve parent segment/image for each member via partOf */
    val parentMap: Map<UUID, UUID> = reader.getConnections(
        subjectIds = memberIds,
        predicates = listOf("partOf"),
        objectIds = emptyList(),
    ).associate { it.subjectId to it.objectId }

    val page = memberIds.drop(offset).take(limit).map { faceId ->
        ClusterMemberItem(
            faceId = faceId.toString(),
            parentId = parentMap[faceId]?.toString(),
        )
    }

    ctx.json(
        ClusterMemberPage(
            clusterId = clusterId.toString(),
            total = memberIds.size,
            limit = limit,
            offset = offset,
            members = page,
        )
    )
}
