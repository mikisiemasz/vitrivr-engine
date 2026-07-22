package org.vitrivr.engine.module.features.feature.external.implementations.face

/**
 * GhostFaceNet 512-d face embeddings (via DeepFace on the Python descriptor server).
 *
 * Detection stays with the shared buffalo_l SCRFD detector; only the embedding network differs.
 */
class FaceEmbeddingGhostfacenet : FaceEmbeddingBase() {
    override val endpointPath: String = "/extract/face_embedding_ghostfacenet"
    override val embeddingDim: Int = 512
}
