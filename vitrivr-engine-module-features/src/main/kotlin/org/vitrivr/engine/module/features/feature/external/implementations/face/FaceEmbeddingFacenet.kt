package org.vitrivr.engine.module.features.feature.external.implementations.face

/**
 * FaceNet 512-d face embeddings (InceptionResnetV1, vggface2 weights).
 *
 * Detection is held constant with ArcFace: the Python endpoint runs the same InsightFace
 * buffalo_l SCRFD detector and only swaps the embedding network, so a face x model comparison
 * varies the recognition layer alone. The endpoint returns the same JSON shape as ArcFace,
 * so no transformer or deserialization change is needed — pointing a schema's face field at
 * this analyser is sufficient to route ingestion and query-by-image through FaceNet.
 */
class FaceEmbeddingFacenet : FaceEmbeddingBase() {
    override val endpointPath: String = "/extract/face_embedding_facenet"
    override val embeddingDim: Int = 512
}
