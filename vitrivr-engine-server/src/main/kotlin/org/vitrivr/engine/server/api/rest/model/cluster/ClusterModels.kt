package org.vitrivr.engine.server.api.rest.model.cluster

import kotlinx.serialization.Serializable

/**
 * Summary of a FACE_CLUSTER_RUN retrievable returned by list / trigger endpoints.
 */
@Serializable
data class ClusterRunSummary(
    val runId: String,
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

/**
 * A single cluster card in the gallery view.
 *
 * [exemplarFaceIds] contains up to N FACE_DETECTION retrievable IDs that serve as
 * visual representatives of the cluster; the UI maps these to thumbnails.
 */
@Serializable
data class ClusterGalleryItem(
    val clusterId: String,
    val memberCount: Int,
    val exemplarFaceIds: List<String>,
    /** Optional label set by the curator via PATCH /clusters/{id}/label. */
    val label: String? = null,
)

/**
 * Paginated gallery of clusters for one run.
 */
@Serializable
data class ClusterGalleryResponse(
    val runId: String,
    val schema: String,
    val totalClusters: Int,
    val limit: Int,
    val offset: Int,
    val clusters: List<ClusterGalleryItem>,
)

/**
 * One member of a cluster (a FACE_DETECTION retrievable).
 *
 * [parentId] is the ID of the VIDEO_SEGMENT or IMAGE the detection belongs to (via `partOf`).
 */
@Serializable
data class ClusterMemberItem(
    val faceId: String,
    val parentId: String?,
)

/**
 * Paginated list of cluster members.
 */
@Serializable
data class ClusterMemberPage(
    val clusterId: String,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val members: List<ClusterMemberItem>,
)
