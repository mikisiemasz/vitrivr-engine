package org.vitrivr.engine.core.features.identity

import org.vitrivr.engine.core.context.Context
import org.vitrivr.engine.core.model.content.element.ContentElement
import org.vitrivr.engine.core.model.descriptor.scalar.StringDescriptor
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
 * Storage-only analyser for the curator-assigned label of a cluster.
 *
 * Written via the REST PATCH /clusters/{id}/label handler; read by the gallery and identify
 * endpoints. Replaces the sidecar JSON `cluster_state` file (see ClusterStateStore).
 */
class ClusterLabel : Analyser<ContentElement<*>, StringDescriptor> {

    override val contentClasses = setOf(ContentElement::class)
    override val descriptorClass = StringDescriptor::class

    override fun prototype(field: Schema.Field<*, *>): StringDescriptor =
        StringDescriptor(UUID.randomUUID(), UUID.randomUUID(), Value.String(""))

    override fun newExtractor(field: Schema.Field<ContentElement<*>, StringDescriptor>, input: Operator<out Retrievable>, context: Context): Extractor<ContentElement<*>, StringDescriptor> =
        throw UnsupportedOperationException("ClusterLabel is storage-only; written by the REST label handler.")

    override fun newExtractor(name: String, input: Operator<out Retrievable>, context: Context): Extractor<ContentElement<*>, StringDescriptor> =
        throw UnsupportedOperationException("ClusterLabel is storage-only; written by the REST label handler.")

    override fun newRetrieverForQuery(field: Schema.Field<ContentElement<*>, StringDescriptor>, query: Query, context: Context): Retriever<ContentElement<*>, StringDescriptor> =
        throw UnsupportedOperationException("ClusterLabel is storage-only; retrieval is not supported.")

    override fun newRetrieverForContent(field: Schema.Field<ContentElement<*>, StringDescriptor>, content: Map<String, ContentElement<*>>, context: Context): Retriever<ContentElement<*>, StringDescriptor> =
        throw UnsupportedOperationException("ClusterLabel is storage-only; retrieval is not supported.")

    override fun newRetrieverForDescriptors(field: Schema.Field<ContentElement<*>, StringDescriptor>, descriptors: Collection<StringDescriptor>, context: Context): Retriever<ContentElement<*>, StringDescriptor> =
        throw UnsupportedOperationException("ClusterLabel is storage-only; retrieval is not supported.")
}
