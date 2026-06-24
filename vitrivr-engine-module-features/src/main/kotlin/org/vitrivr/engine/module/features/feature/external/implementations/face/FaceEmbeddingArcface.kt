package org.vitrivr.engine.module.features.feature.external.implementations.face

/**
 * ArcFace 512-d face embeddings. Default face embedder for vitrivr.
 *
 * The matching Python endpoint is '/extract/face_embedding' (kept as-is for now; will be
 * renamed to '/extract/face_embedding_arcface' when a second embedder is added, alongside a
 * new sibling subclass such as `FaceEmbeddingFacenet`).
 */
class FaceEmbeddingArcface : FaceEmbeddingBase() {
    override val endpointPath: String = "/extract/face_embedding"
    override val embeddingDim: Int = 512
}
