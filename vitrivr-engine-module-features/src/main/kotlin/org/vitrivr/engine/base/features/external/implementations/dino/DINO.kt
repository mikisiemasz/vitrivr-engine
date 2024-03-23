package org.vitrivr.engine.base.features.external.implementations.dino

import org.vitrivr.engine.base.features.external.ExternalAnalyser
import org.vitrivr.engine.base.features.external.common.ExternalWithFloatVectorDescriptorAnalyser
import org.vitrivr.engine.core.context.IndexContext
import org.vitrivr.engine.core.context.QueryContext
import org.vitrivr.engine.core.model.content.Content
import org.vitrivr.engine.core.model.content.element.ContentElement
import org.vitrivr.engine.core.model.content.element.ImageContent
import org.vitrivr.engine.core.model.descriptor.vector.FloatVectorDescriptor
import org.vitrivr.engine.core.model.metamodel.Analyser
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.core.model.query.Query
import org.vitrivr.engine.core.model.query.proximity.ProximityQuery
import org.vitrivr.engine.core.model.retrievable.Retrievable
import org.vitrivr.engine.core.model.types.Value
import org.vitrivr.engine.core.operators.Operator
import org.vitrivr.engine.core.operators.ingest.Extractor
import org.vitrivr.engine.core.operators.retrieve.Retriever
import java.util.*

/**
 * Implementation of the [DINO] [ExternalAnalyser], which derives the DINO feature from an [ImageContent] as [FloatVectorDescriptor].
 *
 * @author Rahel Arnold
 * @version 1.0.0
 */
class DINO : ExternalWithFloatVectorDescriptorAnalyser<ImageContent>() {

    override val contentClasses = setOf(ImageContent::class)
    override val descriptorClass = FloatVectorDescriptor::class

    /**
     * Generates a prototypical [FloatVectorDescriptor] for this [DINO].
     *
     * @return [FloatVectorDescriptor]
     */
    override fun prototype(field: Schema.Field<*, *>) = FloatVectorDescriptor(UUID.randomUUID(), UUID.randomUUID(), List(384) { Value.Float(0.0f) }, true)

    /**
     * Generates and returns a new [Extractor] instance for this [DINO].
     *
     * @param field The [Schema.Field] to create an [Extractor] for.
     * @param input The [Operator] that acts as input to the new [Extractor].
     * @param context The [IndexContext] to use with the [Extractor].
     * @param persisting True, if the results of the [Extractor] should be persisted.
     *
     * @return A new [Extractor] instance for this [DINO]
     * @throws [UnsupportedOperationException], if this [DINO] does not support the creation of an [Extractor] instance.
     */
    override fun newExtractor(field: Schema.Field<ImageContent, FloatVectorDescriptor>, input: Operator<Retrievable>, context: IndexContext, persisting: Boolean, parameters: Map<String, Any>): Extractor<ImageContent, FloatVectorDescriptor> {
        require(field.analyser == this) { "The field '${field.fieldName}' analyser does not correspond with this analyser. This is a programmer's error!" }
        return DINOExtractor(input, field, persisting)
    }

    /**
     * Generates and returns a new [DINORetriever] instance for this [DINO].
     *
     * @param field The [Schema.Field] to create an [Retriever] for.
     * @param query An array of [Query] elements to use with the [Retriever]
     * @param context The [QueryContext] to use with the [Retriever]
     *
     * @return A new [Retriever] instance for this [Analyser]
     * @throws [UnsupportedOperationException], if this [Analyser] does not support the creation of an [Retriever] instance.
     */
    override fun newRetrieverForQuery(field: Schema.Field<ImageContent, FloatVectorDescriptor>, query: Query, context: QueryContext): DINORetriever {
        require(field.analyser == this) { "The field '${field.fieldName}' analyser does not correspond with this analyser. This is a programmer's error!" }
        require(query is ProximityQuery<*> && query.value.first() is Value.Float) { "The query is not a ProximityQuery<Value.Float>." }
        @Suppress("UNCHECKED_CAST")
        return DINORetriever(field, query as ProximityQuery<Value.Float>, context)
    }

    /**
     * Generates and returns a new [DINORetriever] instance for this [DINO].
     *
     * @param field The [Schema.Field] to create an [Retriever] for.
     * @param content An array of [Content] elements to use with the [Retriever]
     * @param context The [QueryContext] to use with the [Retriever]
     *
     * @return A new [Retriever] instance for this [Analyser]
     * @throws [UnsupportedOperationException], if this [Analyser] does not support the creation of an [Retriever] instance.
     */
    override fun newRetrieverForContent(field: Schema.Field<ImageContent, FloatVectorDescriptor>, content: Collection<ImageContent>, context: QueryContext): DINORetriever {
        require(field.analyser == this) { "The field '${field.fieldName}' analyser does not correspond with this analyser. This is a programmer's error!" }
        val host = field.parameters[HOST_PARAMETER_NAME] ?: HOST_PARAMETER_DEFAULT

        /* Prepare query parameters. */
        val vector = analyse(content.first(), host)
        val k = context.getProperty(field.fieldName, "limit")?.toIntOrNull() ?: 1000
        val fetchVector = context.getProperty(field.fieldName, "returnDescriptor")?.toBooleanStrictOrNull() ?: false

        /* Return retriever. */
        return this.newRetrieverForQuery(field, ProximityQuery(value = vector.vector, k = k, fetchVector = fetchVector), context)
    }

    /**
     * Requests the CLIP feature descriptor for the given [ContentElement].
     *
     * @param content The [ImageContent] for which to request the [DINO] feature descriptor.
     * @param hostname The hostname of the external feature descriptor service.
     * @return A list of CLIP feature descriptors.
     */
    override fun analyse(content: ImageContent, hostname: String): FloatVectorDescriptor {
        return FloatVectorDescriptor(UUID.randomUUID(), null, httpRequest(content, "$hostname/extract/dino"), true)
    }
}