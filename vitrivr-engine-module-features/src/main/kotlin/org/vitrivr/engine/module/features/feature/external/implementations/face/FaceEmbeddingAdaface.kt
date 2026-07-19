package org.vitrivr.engine.module.features.feature.external.implementations.face

/**
 * AdaFace IR-101 512-d face embeddings (quality-adaptive margin; strongest published results
 * on low-quality faces, which matches the egocentric CASTLE footage).
 *
 * Detection stays with the shared buffalo_l SCRFD detector; alignment is the same 112x112
 * crop ArcFace uses, so only the embedding network differs. The Python endpoint requires the
 * vendored AdaFace repo + checkpoint (see face_models.py) and returns 500 until deployed.
 */
class FaceEmbeddingAdaface : FaceEmbeddingBase() {
    override val endpointPath: String = "/extract/face_embedding_adaface"
    override val embeddingDim: Int = 512
}
