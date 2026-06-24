package org.vitrivr.engine.core.features.identity

import org.vitrivr.engine.core.context.Context
import org.vitrivr.engine.core.model.content.element.ContentElement
import org.vitrivr.engine.core.model.descriptor.struct.identity.TrackMetaDescriptor
import org.vitrivr.engine.core.model.metamodel.Analyser
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.core.model.query.Query
import org.vitrivr.engine.core.model.retrievable.Retrievable
import org.vitrivr.engine.core.operators.Operator
import org.vitrivr.engine.core.operators.ingest.Extractor
import org.vitrivr.engine.core.operators.retrieve.Retriever
import java.util.*

/**
 * Storage-only analyser for per-track metadata (source video, time range, detection count).
 *
 * Written by the FaceTrackingService when a FACE_TRACK retrievable is materialized.
 */
class TrackMeta : Analyser<ContentElement<*>, TrackMetaDescriptor> {

    override val contentClasses = setOf(ContentElement::class)
    override val descriptorClass = TrackMetaDescriptor::class

    override fun prototype(field: Schema.Field<*, *>): TrackMetaDescriptor =
        TrackMetaDescriptor(UUID.randomUUID(), UUID.randomUUID(), "", 0L, 0L, 0)

    override fun newExtractor(field: Schema.Field<ContentElement<*>, TrackMetaDescriptor>, input: Operator<out Retrievable>, context: Context): Extractor<ContentElement<*>, TrackMetaDescriptor> =
        throw UnsupportedOperationException("TrackMeta is storage-only; written by the tracking service.")

    override fun newExtractor(name: String, input: Operator<out Retrievable>, context: Context): Extractor<ContentElement<*>, TrackMetaDescriptor> =
        throw UnsupportedOperationException("TrackMeta is storage-only; written by the tracking service.")

    override fun newRetrieverForQuery(field: Schema.Field<ContentElement<*>, TrackMetaDescriptor>, query: Query, context: Context): Retriever<ContentElement<*>, TrackMetaDescriptor> =
        throw UnsupportedOperationException("TrackMeta is storage-only; retrieval is not supported.")

    override fun newRetrieverForContent(field: Schema.Field<ContentElement<*>, TrackMetaDescriptor>, content: Map<String, ContentElement<*>>, context: Context): Retriever<ContentElement<*>, TrackMetaDescriptor> =
        throw UnsupportedOperationException("TrackMeta is storage-only; retrieval is not supported.")

    override fun newRetrieverForDescriptors(field: Schema.Field<ContentElement<*>, TrackMetaDescriptor>, descriptors: Collection<TrackMetaDescriptor>, context: Context): Retriever<ContentElement<*>, TrackMetaDescriptor> =
        throw UnsupportedOperationException("TrackMeta is storage-only; retrieval is not supported.")
}
