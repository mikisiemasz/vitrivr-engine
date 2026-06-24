package org.vitrivr.engine.core.features.identity

import org.vitrivr.engine.core.context.Context
import org.vitrivr.engine.core.model.content.element.ContentElement
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
 * Storage-only analyser for per-track aggregated face embeddings (e.g. centroid of all face
 * detections belonging to a single ByteTrack-assigned track).
 *
 * Written by the FaceTrackingService when a FACE_TRACK retrievable is materialized. Dimensionality
 * comes from the field's `length` parameter so the column matches the embedding model in use.
 */
class TrackEmbedding : Analyser<ContentElement<*>, FloatVectorDescriptor> {
    companion object {
        const val LENGTH_PARAMETER = "length"
        const val DEFAULT_LENGTH = 512
    }

    override val contentClasses = setOf(ContentElement::class)
    override val descriptorClass = FloatVectorDescriptor::class

    override fun prototype(field: Schema.Field<*, *>): FloatVectorDescriptor {
        val length = field.parameters[LENGTH_PARAMETER]?.toIntOrNull() ?: DEFAULT_LENGTH
        return FloatVectorDescriptor(UUID.randomUUID(), UUID.randomUUID(), Value.FloatVector(length))
    }

    override fun newExtractor(field: Schema.Field<ContentElement<*>, FloatVectorDescriptor>, input: Operator<out Retrievable>, context: Context): Extractor<ContentElement<*>, FloatVectorDescriptor> =
        throw UnsupportedOperationException("TrackEmbedding is storage-only; written by the tracking service.")

    override fun newExtractor(name: String, input: Operator<out Retrievable>, context: Context): Extractor<ContentElement<*>, FloatVectorDescriptor> =
        throw UnsupportedOperationException("TrackEmbedding is storage-only; written by the tracking service.")

    override fun newRetrieverForQuery(field: Schema.Field<ContentElement<*>, FloatVectorDescriptor>, query: Query, context: Context): Retriever<ContentElement<*>, FloatVectorDescriptor> =
        throw UnsupportedOperationException("TrackEmbedding is storage-only; retrieval is not supported.")

    override fun newRetrieverForContent(field: Schema.Field<ContentElement<*>, FloatVectorDescriptor>, content: Map<String, ContentElement<*>>, context: Context): Retriever<ContentElement<*>, FloatVectorDescriptor> =
        throw UnsupportedOperationException("TrackEmbedding is storage-only; retrieval is not supported.")

    override fun newRetrieverForDescriptors(field: Schema.Field<ContentElement<*>, FloatVectorDescriptor>, descriptors: Collection<FloatVectorDescriptor>, context: Context): Retriever<ContentElement<*>, FloatVectorDescriptor> =
        throw UnsupportedOperationException("TrackEmbedding is storage-only; retrieval is not supported.")
}
