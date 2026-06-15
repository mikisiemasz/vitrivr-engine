package org.vitrivr.engine.module.features.feature.external.implementations.face

import org.vitrivr.engine.core.context.Context
import org.vitrivr.engine.core.model.content.element.ImageContent
import org.vitrivr.engine.core.model.descriptor.vector.FloatVectorDescriptor
import org.vitrivr.engine.core.model.metamodel.Analyser
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.core.model.query.Query
import org.vitrivr.engine.core.model.retrievable.Retrievable
import org.vitrivr.engine.core.model.types.Value
import org.vitrivr.engine.core.operators.Operator
import org.vitrivr.engine.core.operators.ingest.Extractor
import org.vitrivr.engine.core.operators.retrieve.Retriever
import java.util.*

/**
 * Storage-only analyser for per-face bounding boxes.
 *
 * The bounding box is **not** extracted via the regular extractor pipeline. It is written
 * directly by [FaceDetectionTransformer] when it persists each FACE_DETECTION retrievable.
 * This analyser exists only so the schema has a typed field to write into.
 *
 * Stored value: normalized `[x1, y1, x2, y2]` in `[0, 1]^4` against the source frame.
 * The frontend multiplies by the rendered image dimensions to draw the rectangle.
 *
 * Retrieval is intentionally unsupported.
 */
class FaceBoundingBox : Analyser<ImageContent, FloatVectorDescriptor> {

    companion object {
        /** Bounding-box dimensionality: x1, y1, x2, y2. */
        const val DIMENSIONALITY = 4
    }

    override val contentClasses = setOf(ImageContent::class)
    override val descriptorClass = FloatVectorDescriptor::class

    override fun prototype(field: Schema.Field<*, *>): FloatVectorDescriptor =
        FloatVectorDescriptor(UUID.randomUUID(), UUID.randomUUID(), Value.FloatVector(DIMENSIONALITY))

    /* FaceBoundingBox is wired into the pipeline via FaceDetectionTransformer (which writes the
       bbox descriptor directly). No standalone extractor is provided. */
    override fun newExtractor(
        field: Schema.Field<ImageContent, FloatVectorDescriptor>,
        input: Operator<out Retrievable>,
        context: Context
    ): Extractor<ImageContent, FloatVectorDescriptor> =
        throw UnsupportedOperationException(
            "FaceBoundingBox is storage-only. Wire face detection via FaceDetectionTransformer."
        )

    override fun newExtractor(
        name: String,
        input: Operator<out Retrievable>,
        context: Context
    ): Extractor<ImageContent, FloatVectorDescriptor> =
        throw UnsupportedOperationException(
            "FaceBoundingBox is storage-only. Wire face detection via FaceDetectionTransformer."
        )

    override fun newRetrieverForQuery(
        field: Schema.Field<ImageContent, FloatVectorDescriptor>,
        query: Query,
        context: Context
    ): Retriever<ImageContent, FloatVectorDescriptor> =
        throw UnsupportedOperationException("FaceBoundingBox is storage-only; retrieval is not supported.")

    override fun newRetrieverForContent(
        field: Schema.Field<ImageContent, FloatVectorDescriptor>,
        content: Map<String, ImageContent>,
        context: Context
    ): Retriever<ImageContent, FloatVectorDescriptor> =
        throw UnsupportedOperationException("FaceBoundingBox is storage-only; retrieval is not supported.")

    override fun newRetrieverForDescriptors(
        field: Schema.Field<ImageContent, FloatVectorDescriptor>,
        descriptors: Collection<FloatVectorDescriptor>,
        context: Context
    ): Retriever<ImageContent, FloatVectorDescriptor> =
        throw UnsupportedOperationException("FaceBoundingBox is storage-only; retrieval is not supported.")
}
