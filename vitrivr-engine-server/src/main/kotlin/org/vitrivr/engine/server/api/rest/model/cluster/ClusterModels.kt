package org.vitrivr.engine.server.api.rest.model.cluster

import kotlinx.serialization.Serializable

/**
 * Summary of a FACE_CLUSTER_RUN retrievable returned by list / trigger endpoints.
 *
 * [target] is "detections" (per-frame clustering) or "tracks" (per-shot identity merge). The frontend uses
 * this to pick member rendering for the gallery — track-based clusters show track cards, detection-based
 * clusters show face cards.
 */
@Serializable
data class ClusterRunSummary(
    val runId: String,
    val algorithm: String,
    val target: String = "detections",
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

/**
 * Exemplar face referenced by a cluster card, with its parent segment for thumbnailing.
 *
 * For track-clusters, [faceId] holds the *representative* FACE_DETECTION id (one per exemplar
 * track) so the UI can render a thumbnail with the same bbox-on-segment-thumbnail logic as
 * detection-clusters. [trackId] is the originating FACE_TRACK id when applicable, otherwise null;
 * the UI uses it if it ever wants to expand into the track's detail view.
 */
@Serializable
data class ClusterExemplar(
    val faceId: String,
    val parentId: String? = null,
    val bbox: List<Float>? = null,
    val trackId: String? = null,
)

/**
 * A single cluster card in the gallery view.
 *
 * [exemplars] contains up to N centroid-nearest members; the UI maps their `parentId` to a segment thumbnail.
 * For detection-target clusters, members are FACE_DETECTION ids (parentId = segment). For track-target
 * clusters, members are FACE_TRACK ids, and the exemplars' faceId field carries the *representative
 * FACE_DETECTION* of each track (the track's centroid-nearest detection), so the frontend can render
 * track clusters with the same bbox-on-segment-thumbnail logic without branching.
 *
 * [memberType] is "FACE_DETECTION" or "FACE_TRACK" — the frontend uses this to decide whether the cluster
 * detail view should expand into per-track timelines or stay at the per-detection grid.
 */
@Serializable
data class ClusterGalleryItem(
    val clusterId: String,
    val memberCount: Int,
    val memberType: String = "FACE_DETECTION",
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
 * Server-side AND-intersection of cluster memberships.
 * Returns exactly the segments where every cluster in [include]
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
    /** Parent SOURCE retrievable id. Null if the segment has no source link. */
    val sourceId: String? = null,
    /** `file.path` descriptor of the parent SOURCE. Used by the frontend to build a video URL
        and a stable dedupe key. Null when no FileSourceMetadata is available. */
    val filePath: String? = null,
    /** Segment start in nanoseconds (from the segment's `time` descriptor). Null when no
        TemporalMetadata is configured or the segment has none. */
    val startNs: Long? = null,
    /** Segment end in nanoseconds. */
    val endNs: Long? = null,
)

@Serializable
data class ClusterMatchResponse(
    val total: Int,
    val limit: Int,
    val results: List<ClusterMatchHit>,
)

/**
 * Request body for `POST /clusters/identify`.
 *
 * Given a face embedding, returns the clusters whose centroids are most
 * similar to it. The vitrivr-web uses this to turn a manual photo upload into a *label* on an
 * existing cluster, so the resulting gallery entry can take the fast `/clusters/match` path
 * instead of the per-face ANN fallback.
 *
 * Both the query and the centroids are assumed L2-normalized; cosine similarity reduces to a
 * dot product. The query is renormalized server-side as a safety measure.
 */
@Serializable
data class ClusterIdentifyRequest(
    val embedding: List<Float>,
    /** Minimum cosine similarity (inclusive). */
    val threshold: Float = 0.5f,
    /** Max number of matches to return after thresholding, ranked by similarity descending. */
    val topK: Int = 5,
)

@Serializable
data class ClusterIdentifyMatch(
    val clusterId: String,
    val similarity: Float,
    /** Existing curator label, if any. */
    val label: String? = null,
)

@Serializable
data class ClusterIdentifyResponse(
    /** Total number of clusters scored (i.e. centroid count), regardless of threshold. */
    val totalClusters: Int,
    val matches: List<ClusterIdentifyMatch>,
)

/**
 * One person's averaged face embedding, paired with the name to assign on match.
 * Used by the batch endpoint where a folder of photos has been collapsed to a single embedding.
 */
@Serializable
data class ClusterIdentifyCandidate(
    val name: String,
    val embedding: List<Float>,
)

/**
 * Request body for `POST /clusters/identify-batch`.
 *
 * Inverts the [ClusterIdentifyRequest] direction: instead of "given one face, which clusters
 * resemble it?", asks "given these N labelled candidate faces, what's each cluster's best-matching
 * candidate?" Used for the upload curation flow
 */
@Serializable
data class ClusterIdentifyBatchRequest(
    val candidates: List<ClusterIdentifyCandidate>,
    /** Min cosine similarity for a cluster to be considered assigned. */
    val threshold: Float = 0.5f,
)

/** A cluster that matched at least one candidate above threshold. */
@Serializable
data class ClusterIdentifyAssignment(
    val clusterId: String,
    /** Winning candidate name (the one with the highest cosine to this cluster's centroid). */
    val bestName: String,
    val similarity: Float,
    /** Curator label currently set on the cluster, if any. May differ from [bestName]. */
    val existingLabel: String? = null,
)

/** A cluster whose centroid does not resemble any candidate above the threshold. */
@Serializable
data class UnmatchedClusterRow(
    val clusterId: String,
    val label: String? = null,
)

/** One segment in a cluster's video timeline. Nanosecond offsets within the source file. */
@Serializable
data class ClusterTimelineSegment(
    val segmentId: String,
    val startNs: Long,
    val endNs: Long,
    val detectionCount: Int,
)

@Serializable
data class ClusterTimelineVideo(
    val sourceId: String,
    val filePath: String? = null,
    val lastAppearanceNs: Long,
    val segments: List<ClusterTimelineSegment>,
)

@Serializable
data class ClusterTimelineResponse(
    val clusterId: String,
    val videos: List<ClusterTimelineVideo>,
)

@Serializable
data class ClusterIdentifyBatchResponse(
    val totalClusters: Int,
    val assignments: List<ClusterIdentifyAssignment>,
    val unmatched: List<UnmatchedClusterRow>,
)
