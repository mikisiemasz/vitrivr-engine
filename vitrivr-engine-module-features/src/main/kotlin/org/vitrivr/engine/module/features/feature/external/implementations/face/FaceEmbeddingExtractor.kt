package org.vitrivr.engine.module.features.feature.external.implementations.face

import org.vitrivr.engine.core.features.AbstractExtractor
import org.vitrivr.engine.core.model.content.ContentType
import org.vitrivr.engine.core.model.content.element.ImageContent
import org.vitrivr.engine.core.model.descriptor.Descriptor
import org.vitrivr.engine.core.model.descriptor.vector.FloatVectorDescriptor
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.core.model.retrievable.Retrievable
import org.vitrivr.engine.core.operators.Operator
import org.vitrivr.engine.core.operators.ingest.Extractor

/**
 * [FaceEmbeddingExtractor] — returns one [FloatVectorDescriptor] **per detected face**.
 *
 * If a frame contains 3 faces, 3 descriptors are stored, all sharing the same [Retrievable.id].
 * If no faces are detected, an empty list is returned (nothing stored).
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
     * @param retrievable The [Retrievable] to process.
     * @return List of [FloatVectorDescriptor]s — one per detected face, possibly empty.
     */
    override fun extract(retrievable: Retrievable): List<FloatVectorDescriptor> {
        return retrievable.content
            .filterIsInstance<ImageContent>()
            .flatMap { imageContent ->
                // analyse() now returns List<FloatVectorDescriptor>, one per face
                FaceEmbedding.analyse(imageContent, this.host).map { descriptor ->
                    descriptor.copy(
                        retrievableId = retrievable.id,
                        field = this@FaceEmbeddingExtractor.field
                    )
                }
            }
    }
}