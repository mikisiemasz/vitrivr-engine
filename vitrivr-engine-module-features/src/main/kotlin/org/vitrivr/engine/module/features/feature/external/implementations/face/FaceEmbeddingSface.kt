package org.vitrivr.engine.module.features.feature.external.implementations.face

/**
 * SFace 128-d face embeddings (via DeepFace on the Python descriptor server).
 *
 * Detection stays with the shared buffalo_l SCRFD detector; only the embedding network
 * differs. Schemas using this analyser must size their clusterCentroid field to 128.
 */
class FaceEmbeddingSface : FaceEmbeddingBase() {
    override val endpointPath: String = "/extract/face_embedding_sface"
    override val embeddingDim: Int = 128
}
