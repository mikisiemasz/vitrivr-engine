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
    val numLabelsCarried: Int = 0,
    val startedAt: String,
    val completedAt: String,
    val status: String,
)

/** Exemplar face referenced by a cluster card, with its parent segment for thumbnailing. */
@Serializable
data class ClusterExemplar(
    val faceId: String,
    val parentId: String? = null,
)

/**
 * A single cluster card in the gallery view.
 *
 * [exemplars] contains up to N centroid-nearest face detections; the UI maps their
 * `parentId` to a segment thumbnail.
 */
@Serializable
data class ClusterGalleryItem(
    val clusterId: String,
    val memberCount: Int,
    val exemplars: List<ClusterExemplar>,
    /** Optional label set by the curator via PATCH /clusters/{id}/label. */
    val label: String? = null,
    /** Distinct segments this cluster appears in (count of unique partOf parents). */
    val segmentCount: Int = 0,
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
 * [bbox] (when present) is `[x1, y1, x2, y2]` normalized to `[0, 1]^4` against the source frame.
 * Render by multiplying by the displayed image dimensions.
 */
@Serializable
data class ClusterMemberItem(
    val faceId: String,
    val parentId: String?,
    val bbox: List<Float>? = null,
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

/**
 * One row of the deduplicated "segments this cluster appears in" view.
 *
 * [parentId] is the parent retrievable's ID (typically a video segment or image).
 * [detectionCount] is how many FACE_DETECTION members of the cluster fall inside this parent.
 */
@Serializable
data class ClusterSegmentItem(
    val parentId: String,
    val detectionCount: Int,
)

@Serializable
data class ClusterSegmentPage(
    val clusterId: String,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val segments: List<ClusterSegmentItem>,
)

/**
 * Co-occurrence partner row.
 *
 * [sharedSegments] is the number of parent retrievables in which both clusters have at least
 * one member detection.
 */
@Serializable
data class CoOccurrenceItem(
    val clusterId: String,
    val label: String? = null,
    val memberCount: Int,
    val sharedSegments: Int,
)

@Serializable
data class CoOccurrenceResponse(
    val clusterId: String,
    val total: Int,
    val partners: List<CoOccurrenceItem>,
)

/**
 * Request body for PATCH /clusters/{id}/label.
 *
 * Send `null` or empty string to clear the label.
 */
@Serializable
data class ClusterLabelPatch(
    val label: String?,
)

/**
 * Request body for POST /clusters/merge.
 *
 * All [clusterIds] must belong to the same schema. The first ID is treated as the survivor when
 * preserving identity; a new cluster is created and existing ones become empty (members and
 * exemplars get rewritten to the new cluster).
 */
@Serializable
data class ClusterMergeRequest(
    val clusterIds: List<String>,
    val label: String? = null,
)

/**
 * Request body for POST /clusters/{id}/split.
 *
 * Moves the listed [faceIds] out of the source cluster into a new cluster.
 */
@Serializable
data class ClusterSplitRequest(
    val faceIds: List<String>,
    val newLabel: String? = null,
)

@Serializable
data class ClusterMutationResult(
    val newClusterId: String,
    val movedMembers: Int,
    val message: String? = null,
)

/** Cluster centroid response — used by the frontend to add a cluster to the face gallery. */
@Serializable
data class ClusterCentroidResponse(
    val clusterId: String,
    val embedding: List<Float>,
)

/**
 * One bin in the group-size histogram. [k] is the number of distinct clusters present in a
 * segment, [segmentCount] is how many segments have exactly that many distinct clusters.
 */
@Serializable
data class GroupSizeBin(val k: Int, val segmentCount: Int)

/** Response for `GET /clusters/stats/group-sizes`. */
@Serializable
data class GroupSizeHistogramResponse(
    val totalSegments: Int,
    val bins: List<GroupSizeBin>,
)

/**
 * Request body for `POST /clusters/match`.
 *
 * Server-side AND-intersection of cluster memberships, replacing the multi-query intersection
 * the frontend currently does. Returns exactly the segments where every cluster in [include]
 * has at least one member and none of the clusters in [exclude] do.
 *
 * Optional [spatialOrder] adds a left-to-right (or top-to-bottom) constraint: among the segments
 * that satisfy the include/exclude clauses, only segments where the listed clusters appear in
 * the requested order along [axis] are returned. The score is the mean margin between
 * consecutive positions — larger is cleaner.
 */
@Serializable
data class ClusterMatchRequest(
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val spatialOrder: List<String>? = null,
    val axis: String = "x",
    val limit: Int = 200,
)

@Serializable
data class ClusterMatchHit(
    val segmentId: String,
    val score: Float,
)

@Serializable
data class ClusterMatchResponse(
    val total: Int,
    val limit: Int,
    val results: List<ClusterMatchHit>,
)
