package org.vitrivr.engine.module.features.feature.external.implementations.face

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
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
import org.vitrivr.engine.module.features.feature.external.logger
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * [ExternalAnalyser] for ArcFace-based face embeddings.
 *
 * Communicates with `/extract/face_embedding` on the Python descriptor server.
 * Returns one 512-d [FloatVectorDescriptor] **per detected face** (not a mean).
 * If no faces are found, returns an empty list.
 */
class FaceEmbedding : ExternalAnalyser<ImageContent, FloatVectorDescriptor>() {

    companion object {
        /**
         * Requests per-face ArcFace embeddings for the given [ContentElement].
         *
         * The Python server returns a JSON array of float arrays:
         *   - 0 faces → `[]`
         *   - N faces → `[[512 floats], [512 floats], ...]`
         *
         * @param content The [ContentElement] for which to request face embeddings.
         * @param hostname The hostname of the external feature descriptor service.
         * @return A list of 512-d [FloatVectorDescriptor]s, one per detected face.
         */
        fun analyse(content: ContentElement<*>, hostname: String): List<FloatVectorDescriptor> {
            val requestBody = when (content) {
                is ImageContent -> URLEncoder.encode(content.toDataUrl(), StandardCharsets.UTF_8.toString())
                else -> throw IllegalArgumentException("Content '$content' not supported")
            }
            val url = "$hostname/extract/face_embedding"
            return httpRequestMulti(url, "data=$requestBody")
        }

        /**
         * HTTP request that deserializes a JSON array of float arrays into
         * a list of [FloatVectorDescriptor]s.
         */
        private fun httpRequestMulti(url: String, requestBody: String): List<FloatVectorDescriptor> = runBlocking {
            val body = requestBody.toByteArray(StandardCharsets.UTF_8)
            val client = try {
                HttpClient(CIO) {
                    install(HttpRequestRetry) {
                        retryOnServerErrors(maxRetries = 5)
                        exponentialDelay()
                    }
                    defaultRequest {
                        header("Content-Type", "application/x-www-form-urlencoded")
                    }
                }
            } catch (e: Throwable) {
                logger.error(e) { "Failed to initialize HTTP client for $url." }
                return@runBlocking emptyList()
            }

            try {
                val response = client.request(url) {
                    method = HttpMethod.Post
                    setBody(body)
                }

                if (!response.status.isSuccess()) {
                    logger.warn { "Non-success response: ${response.status.value} from $url" }
                    return@runBlocking emptyList()
                }

                val bytes = response.bodyAsChannel().toInputStream()
                val embeddings: List<FloatArray> = bytes.use { stream ->
                    Json.decodeFromStream<List<FloatArray>>(stream)
                }

                embeddings.map { emb ->
                    FloatVectorDescriptor(
                        UUID.randomUUID(),
                        null,
                        Value.FloatVector(emb)
                    )
                }
            } catch (e: Throwable) {
                logger.error(e) { "Error during face embedding API call to $url." }
                emptyList()
            } finally {
                client.close()
            }
        }
    }

    override val contentClasses = setOf(ImageContent::class)
    override val descriptorClass = FloatVectorDescriptor::class

    override fun prototype(field: Schema.Field<*, *>) =
        FloatVectorDescriptor(UUID.randomUUID(), UUID.randomUUID(), Value.FloatVector(512))

    override fun newExtractor(
        field: Schema.Field<ImageContent, FloatVectorDescriptor>,
        input: Operator<out Retrievable>,
        context: Context
    ): FaceEmbeddingExtractor {
        val host: String = field.parameters[HOST_PARAMETER_NAME] ?: HOST_PARAMETER_DEFAULT
        return FaceEmbeddingExtractor(input, this, field, host)
    }

    override fun newExtractor(
        name: String,
        input: Operator<out Retrievable>,
        context: Context
    ): FaceEmbeddingExtractor {
        val host: String = context.getProperty(name, HOST_PARAMETER_NAME) ?: HOST_PARAMETER_DEFAULT
        return FaceEmbeddingExtractor(input, this, name, host)
    }

    override fun newRetrieverForQuery(
        field: Schema.Field<ImageContent, FloatVectorDescriptor>,
        query: Query,
        context: Context
    ): DenseRetriever<ImageContent> {
        require(query is ProximityQuery<*> && query.value is Value.FloatVector) {
            "The query is not a ProximityQuery<Value.FloatVector>."
        }
        @Suppress("UNCHECKED_CAST")
        return DenseRetriever(
            field,
            query as ProximityQuery<Value.FloatVector>,
            context,
            BoundedCorrespondence(0.0, 2.0)
        )
    }

    override fun newRetrieverForContent(
        field: Schema.Field<ImageContent, FloatVectorDescriptor>,
        content: Map<String, ImageContent>,
        context: Context
    ): DenseRetriever<ImageContent> {
        val host = field.parameters[HOST_PARAMETER_NAME] ?: HOST_PARAMETER_DEFAULT
        // For retrieval, use the first face from the query image
        val descriptors = content.values.flatMap { analyse(it, host) }
        require(descriptors.isNotEmpty()) { "No faces detected in the query image." }
        return newRetrieverForDescriptors(field, descriptors, context)
    }

    override fun newRetrieverForDescriptors(
        field: Schema.Field<ImageContent, FloatVectorDescriptor>,
        descriptors: Collection<FloatVectorDescriptor>,
        context: Context
    ): DenseRetriever<ImageContent> {
        val k = context.getProperty(field.fieldName, "limit")?.toLongOrNull() ?: 1000L
        val fetchVector = context.getProperty(field.fieldName, "returnDescriptor")?.toBooleanStrictOrNull() ?: false
        // Use the first descriptor as the query vector
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