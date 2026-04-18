package org.vitrivr.engine.module.features.feature.external.implementations.face

import org.vitrivr.engine.core.context.Context
import org.vitrivr.engine.core.features.dense.DenseRetriever
import org.vitrivr.engine.core.math.correspondence.BoundedCorrespondence
import org.vitrivr.engine.core.model.content.element.ContentElement
import org.vitrivr.engine.core.model.content.element.ImageContent
import org.vitrivr.engine.core.model.descriptor.vector.FloatVectorDescriptor
import org.vitrivr.engine.core.model.metamodel.Analyser
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.core.model.query.Query
import org.vitrivr.engine.core.model.query.basics.Distance
import org.vitrivr.engine.core.model.query.proximity.ProximityQuery
import org.vitrivr.engine.core.model.retrievable.Retrievable
import org.vitrivr.engine.core.model.types.Value
import org.vitrivr.engine.core.operators.Operator
import org.vitrivr.engine.core.operators.ingest.Extractor
import org.vitrivr.engine.core.operators.retrieve.Retriever
import org.vitrivr.engine.module.features.feature.external.ExternalAnalyser
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * [ExternalAnalyser] for ArcFace-based face embeddings.
 *
 * Communicates with `/extract/face_embedding` on the Python descriptor server.
 * Returns a single 512-d [FloatVectorDescriptor] (L2-normalised mean of all detected faces).
 */
class FaceEmbedding : ExternalAnalyser<ImageContent, FloatVectorDescriptor>() {

    companion object {
        /**
         * Requests the ArcFace embedding for the given [ContentElement].
         *
         * @param content The [ContentElement] for which to request the face embedding.
         * @param hostname The hostname of the external feature descriptor service.
         * @return A 512-d [FloatVectorDescriptor].
         */
        fun analyse(content: ContentElement<*>, hostname: String): FloatVectorDescriptor {
            val requestBody = when (content) {
                is ImageContent -> URLEncoder.encode(content.toDataUrl(), StandardCharsets.UTF_8.toString())
                else -> throw IllegalArgumentException("Content '$content' not supported")
            }
            val url = "$hostname/extract/face_embedding"
            return httpRequest<FloatVectorDescriptor>(url, "data=$requestBody")
                ?: throw IllegalArgumentException("Failed to generate FaceEmbedding descriptor.")
        }
    }

    override val contentClasses = setOf(ImageContent::class)
    override val descriptorClass = FloatVectorDescriptor::class

    /**
     * Generates a prototypical 512-d [FloatVectorDescriptor] for this [FaceEmbedding].
     *
     * @return [FloatVectorDescriptor]
     */
    override fun prototype(field: Schema.Field<*, *>) =
        FloatVectorDescriptor(UUID.randomUUID(), UUID.randomUUID(), Value.FloatVector(512))

    /**
     * Generates and returns a new [FaceEmbeddingExtractor] instance for this [FaceEmbedding].
     *
     * @param field The [Schema.Field] to create an [Extractor] for.
     * @param input The [Operator] that acts as input to the new [Extractor].
     * @param context The [Context] to use with the [Extractor].
     * @return [FaceEmbeddingExtractor]
     */
    override fun newExtractor(
        field: Schema.Field<ImageContent, FloatVectorDescriptor>,
        input: Operator<out Retrievable>,
        context: Context
    ): FaceEmbeddingExtractor {
        val host: String = field.parameters[HOST_PARAMETER_NAME] ?: HOST_PARAMETER_DEFAULT
        return FaceEmbeddingExtractor(input, this, field, host)
    }

    /**
     * Generates and returns a new [FaceEmbeddingExtractor] instance for this [FaceEmbedding].
     *
     * @param name The name of the [Extractor].
     * @param input The [Operator] that acts as input to the new [Extractor].
     * @param context The [Context] to use with the [Extractor].
     * @return [FaceEmbeddingExtractor]
     */
    override fun newExtractor(
        name: String,
        input: Operator<out Retrievable>,
        context: Context
    ): FaceEmbeddingExtractor {
        val host: String = context.getProperty(name, HOST_PARAMETER_NAME) ?: HOST_PARAMETER_DEFAULT
        return FaceEmbeddingExtractor(input, this, name, host)
    }

    /**
     * Generates and returns a new [DenseRetriever] instance for this [FaceEmbedding].
     *
     * @param field The [Schema.Field] to create a [Retriever] for.
     * @param query The [Query] to use with the [Retriever].
     * @param context The [Context] to use with the [Retriever].
     * @return A new [DenseRetriever] instance for this [Analyser].
     */
    override fun newRetrieverForQuery(
        field: Schema.Field<ImageContent, FloatVectorDescriptor>,
        query: Query,
        context: Context
    ): DenseRetriever<ImageContent> {
        require(query is ProximityQuery<*> && query.value is Value.FloatVector) { "The query is not a ProximityQuery<Value.FloatVector>." }
        @Suppress("UNCHECKED_CAST")
        return DenseRetriever(
            field,
            query as ProximityQuery<Value.FloatVector>,
            context,
            BoundedCorrespondence(0.0, 2.0)
        )
    }

    /**
     * Generates and returns a new [DenseRetriever] instance for this [FaceEmbedding] from [ContentElement]s.
     *
     * @param field The [Schema.Field] to create a [Retriever] for.
     * @param content A map of [ContentElement] elements to use with the [Retriever].
     * @param context The [Context] to use with the [Retriever].
     * @return A new [DenseRetriever] instance for this [Analyser].
     */
    override fun newRetrieverForContent(
        field: Schema.Field<ImageContent, FloatVectorDescriptor>,
        content: Map<String, ImageContent>,
        context: Context
    ): DenseRetriever<ImageContent> {
        val host = field.parameters[HOST_PARAMETER_NAME] ?: HOST_PARAMETER_DEFAULT
        val descriptors = content.values.map { analyse(it, host) }
        return newRetrieverForDescriptors(field, descriptors, context)
    }

    /**
     * Generates and returns a new [DenseRetriever] instance for this [FaceEmbedding] from [FloatVectorDescriptor]s.
     *
     * @param field The [Schema.Field] to create a [Retriever] for.
     * @param descriptors A collection of [FloatVectorDescriptor] elements to use with the [Retriever].
     * @param context The [Context] to use with the [Retriever].
     * @return A new [DenseRetriever] instance for this [Analyser].
     */
    override fun newRetrieverForDescriptors(
        field: Schema.Field<ImageContent, FloatVectorDescriptor>,
        descriptors: Collection<FloatVectorDescriptor>,
        context: Context
    ): DenseRetriever<ImageContent> {
        val k = context.getProperty(field.fieldName, "limit")?.toLongOrNull() ?: 1000L
        val fetchVector = context.getProperty(field.fieldName, "returnDescriptor")?.toBooleanStrictOrNull() ?: false
        return this.newRetrieverForQuery(
            field,
            ProximityQuery(
                value = descriptors.first().vector,
                k = k,
                distance = Distance.COSINE,
                fetchVector = fetchVector
            ),
            context
        )
    }
}