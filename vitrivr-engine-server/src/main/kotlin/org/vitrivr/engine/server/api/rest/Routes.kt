package org.vitrivr.engine.server.api.rest

import io.javalin.apibuilder.ApiBuilder.*
import org.vitrivr.engine.core.config.pipeline.execution.ExecutionServer
import org.vitrivr.engine.core.model.metamodel.SchemaManager
import org.vitrivr.engine.server.api.rest.handlers.*
import org.vitrivr.engine.server.api.rest.handlers.listClusterRuns
import org.vitrivr.engine.server.api.rest.handlers.triggerClustering
import org.vitrivr.engine.server.api.rest.handlers.listClusters
import org.vitrivr.engine.server.api.rest.handlers.getClusterMembers
import org.vitrivr.engine.server.config.ApiConfig


/**
 * Configures all the API routes.
 *
 * @param config The [ApiConfig] used for persistence.
 * @param manager The [SchemaManager] used for persistence.
 */
fun configureApiRoutes(config: ApiConfig, manager: SchemaManager, executor: ExecutionServer) {
    path("api") {
        /* Add global routes (non-schema specific). */
        path("schema") {
            get("list") { ctx -> listSchemas(ctx, manager) }
        }

        /* Add global routes (module specific). */
        if (config.retrieval) {
            /* TODO: Retrieval-only, global routes. */
        }
        if (config.index) {
            /* TODO: Index-only, global routes. */
        }

        /* Add schema specific routes. */
        for (schema in manager.listSchemas()) {
            path(schema.name) {
                if (config.index) {
                    path("index") { // This is a more specific path, hence it has to be registered earlier
                        get("{jobId}") { ctx -> executeIngestStatus(ctx, executor) }
                    }
                    post("index") { ctx -> executeIngest(ctx, schema, executor) }
                }

                if (config.retrieval) {
                    post("query") { ctx -> executeQuery(ctx, schema, executor) }
                }

                /* Cluster endpoints (always enabled when retrieval or index is active) */
                if (config.retrieval || config.index) {
                    path("clusters") {
                        get { ctx -> listClusters(ctx, schema) }
                        get("runs") { ctx -> listClusterRuns(ctx, schema) }
                        post("run") { ctx -> triggerClustering(ctx, schema) }
                        post("merge") { ctx -> mergeClusters(ctx, schema) }
                        get("{clusterId}/members") { ctx -> getClusterMembers(ctx, schema) }
                        get("{clusterId}/centroid") { ctx -> getClusterCentroid(ctx, schema) }
                        get("{clusterId}/segments") { ctx -> getClusterSegments(ctx, schema) }
                        get("{clusterId}/co-occurrences") { ctx -> getClusterCoOccurrences(ctx, schema) }
                        get("stats/group-sizes") { ctx -> getGroupSizeHistogram(ctx, schema) }
                        post("match") { ctx -> matchClusters(ctx, schema) }
                        patch("{clusterId}/label") { ctx -> patchClusterLabel(ctx, schema) }
                        post("{clusterId}/split") { ctx -> splitCluster(ctx, schema) }
                    }
                }

                if (config.export) {

                        get(FETCH_ROUTE_FROM_SCHEMA) { ctx ->
                            fetchExportData(ctx, schema)
                        }
                }
            }
        }
    }
}
