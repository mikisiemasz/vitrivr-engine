package org.vitrivr.engine.module.features.feature.external.implementations.face

import org.vitrivr.engine.core.features.AbstractExtractor
import org.vitrivr.engine.core.model.content.ContentType
import org.vitrivr.engine.core.model.content.element.ImageContent
import org.vitrivr.engine.core.model.descriptor.vector.FloatVectorDescriptor
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.core.model.retrievable.Retrievable
import org.vitrivr.engine.core.model.types.Value
import org.vitrivr.engine.core.operators.Operator
import java.util.*

/**
 * [FaceEmbeddingExtractor] — returns one [FloatVectorDescriptor] **per detected face**.
 *
 * This extractor attaches face embeddings to the incoming retrievable. Each descriptor shares the parent retrievable's ID.
 *
 * After re-extraction using [FaceDetectionTransformer], face embeddings will instead
 * be stored on dedicated FACE_DETECTION retrievables with a `partOf` relationship to
 * the parent. Until then, this extractor keeps the legacy pipeline working. 
 * TODO: remove the previous version support
 */
class FaceEmbeddingExtractor : AbstractExtractor<ImageContent, FloatVectorDescriptor> {

    private val host: String

    constructor(
        input: Operator<out Retrievable>,
        analyser: FaceEmbedding,
        field: Schema.Field<ImageContent, FloatVectorDescriptor>,
        host: String
    ) : super(input, analyser, field) {
        this.host = host
    }

    constructor(
        input: Operator<out Retrievable>,
        analyser: FaceEmbedding,
        name: String,
        host: String
    ) : super(input, analyser, name) {
        this.host = host
    }

    override fun matches(retrievable: Retrievable): Boolean =
        retrievable.content.any { it.type == ContentType.BITMAP_IMAGE }

    /**
     * Extracts face embeddings from the [Retrievable].
     *
     * Calls the Python server via [FaceEmbedding.analyse], maps each [FaceDetectionResult]
     * to a [FloatVectorDescriptor] bound to the parent retrievable's ID.
     *
     * @param retrievable The [Retrievable] to process.
     * @return List of [FloatVectorDescriptor]s — one per detected face, possibly empty.
     */
    override fun extract(retrievable: Retrievable): List<FloatVectorDescriptor> =
        retrievable.content
            .filterIsInstance<ImageContent>()
            .flatMap { imageContent ->
                FaceEmbedding.analyse(imageContent, this.host).map { det ->
                    FloatVectorDescriptor(
                        id = UUID.randomUUID(),
                        retrievableId = retrievable.id,
                        vector = Value.FloatVector(det.embedding.toFloatArray()),
                        field = this@FaceEmbeddingExtractor.field
                    )
                }
            }
}