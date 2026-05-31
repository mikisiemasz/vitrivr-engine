package org.vitrivr.engine.module.features.feature.external.implementations.face

import kotlinx.serialization.Serializable

/**
 * Per-face result returned by the Python descriptor server's `/extract/face_embedding` endpoint.
 *
 * The server runs InsightFace buffalo_l and returns one object per detected face.
 *
 * @property embedding Normalized 512-d ArcFace embedding vector.
 * @property bbox      Bounding box [x1, y1, x2, y2] in pixel coordinates.
 * @property score     InsightFace detection confidence score.
 *
 */
@Serializable
data class FaceDetectionResult(
    val embedding: List<Float>,
    val bbox: List<Float>,
    val score: Float,
)