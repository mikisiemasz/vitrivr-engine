package org.vitrivr.engine.module.features.feature.external.implementations.face

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.vitrivr.engine.core.context.Context
import org.vitrivr.engine.core.features.dense.DenseRetriever
import org.vitrivr.engine.core.math.correspondence.BoundedCorrespondence
import org.vitrivr.engine.core.model.content.element.ContentElement
import org.vitrivr.engine.core.model.content.element.ImageContent
import org.vitrivr.engine.core.model.descriptor.vector.FloatVectorDescriptor
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.core.model.query.Query
import org.vitrivr.engine.core.model.query.proximity.ProximityQuery
import org.vitrivr.engine.core.model.retrievable.Retrievable
import org.vitrivr.engine.core.model.types.Value
import org.vitrivr.engine.core.operators.Operator
import org.vitrivr.engine.core.operators.ingest.Extractor
import org.vitrivr.engine.module.features.feature.external.ExternalAnalyser
import org.vitrivr.engine.module.features.feature.external.logger
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Abstract base for face-embedding [ExternalAnalyser]s. Concrete subclasses pick a Python
 * endpoint path and the embedding dimensionality, so multiple face models can coexist on the
 * same descriptor server (e.g. ArcFace on '/extract/face_embedding_arcface', FaceNet on
 * '/extract/face_embedding_facenet').
 *
 * The HTTP plumbing and retriever wiring live here; subclasses only declare what makes them
 * different. Pipeline ingestion is wired via [FaceDetectionTransformer], not via a per-class
 * extractor — that's why [newExtractor] throws.
 */
abstract class FaceEmbeddingBase : ExternalAnalyser<ImageContent, FloatVectorDescriptor>() {

    /** Path appended to the host URL to reach this model's endpoint on the Python server. */
    abstract val endpointPath: String

    /** Embedding vector length produced by this model. Drives [prototype] and the pgvector column width. */
    abstract val embeddingDim: Int

    companion object {

        /**
         * Requests per-face detections + embeddings for the given [ContentElement].
         *
         * @param content      The [ContentElement] for which to request face detections.
         * @param hostname     Host URL of the Python descriptor server, e.g. `http://127.0.0.1:8888`.
         * @param endpointPath Path on the server, e.g. `/extract/face_embedding_arcface`.
         */
        fun analyse(content: ContentElement<*>, hostname: String, endpointPath: String): List<FaceDetectionResult> {
            val requestBody = when (content) {
                is ImageContent -> URLEncoder.encode(content.toDataUrl(), StandardCharsets.UTF_8.toString())
                else -> throw IllegalArgumentException("Content '$content' not supported")
            }
            return httpRequestDetailed("$hostname$endpointPath", "data=$requestBody")
        }

        /**
         * Convenience: calls [analyse] and projects to embedding-only descriptors. Used by the
         * content-side retriever where bbox isn't needed.
         */
        fun analyseEmbeddings(content: ContentElement<*>, hostname: String, endpointPath: String): List<FloatVectorDescriptor> =
            analyse(content, hostname, endpointPath).map { det ->
                FloatVectorDescriptor(UUID.randomUUID(), null, Value.FloatVector(det.embedding.toFloatArray()))
            }

        @OptIn(ExperimentalSerializationApi::class)
        private fun httpRequestDetailed(url: String, requestBody: String): List<FaceDetectionResult> = runBlocking {
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
                response.bodyAsChannel().toInputStream().use { stream ->
                    Json.decodeFromStream<List<FaceDetectionResult>>(stream)
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
        FloatVectorDescriptor(UUID.randomUUID(), UUID.randomUUID(), Value.FloatVector(embeddingDim))

    /* Wired into the pipeline via FaceDetectionTransformer, not a standalone extractor. */
    override fun newExtractor(
        field: Schema.Field<ImageContent, FloatVectorDescriptor>,
        input: Operator<out Retrievable>,
        context: Context,
    ): Extractor<ImageContent, FloatVectorDescriptor> =
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not provide a standalone extractor. " +
                "Wire face extraction via FaceDetectionTransformer in your ingest pipeline."
        )

    override fun newExtractor(
        name: String,
        input: Operator<out Retrievable>,
        context: Context,
    ): Extractor<ImageContent, FloatVectorDescriptor> =
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not provide a standalone extractor. " +
                "Wire face extraction via FaceDetectionTransformer in your ingest pipeline."
        )

    override fun newRetrieverForQuery(
        field: Schema.Field<ImageContent, FloatVectorDescriptor>,
        query: Query,
        context: Context,
    ): DenseRetriever<ImageContent> {
        require(query is ProximityQuery<*> && query.value is Value.FloatVector) {
            "The query is not a ProximityQuery<Value.FloatVector>."
        }
        @Suppress("UNCHECKED_CAST")
        return DenseRetriever(
            field,
            query as ProximityQuery<Value.FloatVector>,
            context,
            BoundedCorrespondence(0.0, 2.0),
        )
    }

    override fun newRetrieverForContent(
        field: Schema.Field<ImageContent, FloatVectorDescriptor>,
        content: Map<String, ImageContent>,
        context: Context,
    ): DenseRetriever<ImageContent> {
        val host = field.parameters[HOST_PARAMETER_NAME] ?: HOST_PARAMETER_DEFAULT
        val descriptors = content.values.flatMap { analyseEmbeddings(it, host, endpointPath) }
        require(descriptors.isNotEmpty()) { "No faces detected in the query image." }
        return newRetrieverForDescriptors(field, descriptors, context)
    }

    override fun newRetrieverForDescriptors(
        field: Schema.Field<ImageContent, FloatVectorDescriptor>,
        descriptors: Collection<FloatVectorDescriptor>,
        context: Context,
    ): DenseRetriever<ImageContent> {
        val k = context.getProperty(field.fieldName, "limit")?.toLongOrNull() ?: 1000L
        val fetchVector = context.getProperty(field.fieldName, "returnDescriptor")?.toBooleanStrictOrNull() ?: false
        return this.newRetrieverForQuery(
            field,
            ProximityQuery(
                value = descriptors.first().vector,
                k = k,
                distance = org.vitrivr.engine.core.model.query.basics.Distance.COSINE,
                fetchVector = fetchVector,
            ),
            context,
        )
    }
}
