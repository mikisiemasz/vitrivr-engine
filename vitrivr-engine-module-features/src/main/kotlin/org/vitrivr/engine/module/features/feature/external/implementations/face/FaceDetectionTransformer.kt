package org.vitrivr.engine.module.features.feature.external.implementations.face

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.vitrivr.engine.core.context.Context
import org.vitrivr.engine.core.database.retrievable.RetrievableWriter
import org.vitrivr.engine.core.model.content.ContentType
import org.vitrivr.engine.core.model.content.element.ImageContent
import org.vitrivr.engine.core.model.descriptor.vector.FloatVectorDescriptor
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.core.model.relationship.Relationship
import org.vitrivr.engine.core.model.retrievable.Ingested
import org.vitrivr.engine.core.model.retrievable.Retrievable
import org.vitrivr.engine.core.model.types.Value
import org.vitrivr.engine.core.operators.Operator
import org.vitrivr.engine.core.operators.OperatorFactory
import org.vitrivr.engine.core.operators.general.Transformer
import org.vitrivr.engine.module.features.feature.external.ExternalAnalyser.Companion.HOST_PARAMETER_DEFAULT
import org.vitrivr.engine.module.features.feature.external.ExternalAnalyser.Companion.HOST_PARAMETER_NAME
import java.util.*

/**
 * A [Transformer] that expands incoming segment or image retrievables into first-class
 * FACE_DETECTION retrievables — one per detected face — and persists them directly.
 *
 * **How it differs from [FaceEmbeddingExtractor]**:
 * [FaceEmbeddingExtractor] attaches multiple descriptors to the *same* parent retrievable.
 * This transformer creates a *new* [Ingested] per face with type "FACE_DETECTION" and writes
 * it (plus the face embedding descriptor and a `partOf` relationship to the parent) to the
 * database immediately. The original parent retrievable is passed downstream unchanged so
 * that subsequent extractors (CLIP, time, file, …) still operate on it normally.
 *
 * **Pipeline placement**: this transformer must be placed *after*
 * `PersistRetrievableTransformer` so the parent already exists in the DB when the
 * `partOf` relationship is written.
 *
 * **Schema config parameters**:
 * - `host`           – Python descriptor server URL (default: http://localhost:8888/)
 * - `embeddingField` – name of the FloatVectorDescriptor field for face embeddings (default: "face")
 *
 */
class FaceDetectionTransformer : OperatorFactory {

    override fun newOperator(name: String, inputs: Map<String, Operator<out Retrievable>>, context: Context): Transformer {
        val host = context[name, HOST_PARAMETER_NAME] ?: HOST_PARAMETER_DEFAULT
        val embeddingFieldName = context[name, "embeddingField"] ?: "face"
        return Instance(inputs.values.first() as Operator<Retrievable>, name, context, host, embeddingFieldName)
    }

    /**
     * Internal [Transformer] instance.
     */
    private class Instance(
        override val input: Operator<out Retrievable>,
        override val name: String,
        private val context: Context,
        private val host: String,
        private val embeddingFieldName: String,
    ) : Transformer {

        private val logger: KLogger = KotlinLogging.logger("FaceDetectionTransformer#$name")

        /** Lazily initialised [RetrievableWriter] for persisting FACE_DETECTION retrievables. */
        private val retrievableWriter: RetrievableWriter by lazy {
            context.schema.connection.getRetrievableWriter()
        }

        /**
         * Lazily resolved embedding field. Null when the field is not configured in the schema —
         * in that case embeddings are still extracted but not persisted as descriptors.
         */
        @Suppress("UNCHECKED_CAST")
        private val embeddingField: Schema.Field<ImageContent, FloatVectorDescriptor>? by lazy {
            context.schema[embeddingFieldName] as? Schema.Field<ImageContent, FloatVectorDescriptor>
        }

        override fun toFlow(scope: CoroutineScope): Flow<Retrievable> =
            this.input.toFlow(scope).map { retrievable ->
                // Only process segments / images that carry image content
                val imageContent = retrievable.content
                    .filterIsInstance<ImageContent>()
                    .firstOrNull { it.type == ContentType.BITMAP_IMAGE }

                if (imageContent == null) {
                    return@map retrievable
                }

                val detections = try {
                    FaceEmbedding.analyse(imageContent, host)
                } catch (e: Throwable) {
                    logger.error(e) { "Face detection call failed for retrievable ${retrievable.id}" }
                    emptyList()
                }

                if (detections.isEmpty()) return@map retrievable

                val writer = embeddingField?.getWriter()

                for (det in detections) {
                    val faceId = UUID.randomUUID()
                    val detection = Ingested(faceId, "FACE_DETECTION", transient = false)

                    // Persist the FACE_DETECTION retrievable
                    retrievableWriter.add(detection)

                    // Persist the partOf relationship: FACE_DETECTION -> parent segment/image
                    retrievableWriter.connect(
                        Relationship.ById(faceId, "partOf", retrievable.id, transient = false)
                    )

                    // Persist the face embedding descriptor
                    writer?.add(
                        FloatVectorDescriptor(
                            id = UUID.randomUUID(),
                            retrievableId = faceId,
                            vector = Value.FloatVector(det.embedding.toFloatArray()),
                            field = embeddingField
                        )
                    )
                }

                logger.trace { "Persisted ${detections.size} FACE_DETECTION(s) for retrievable ${retrievable.id}" }

                // Pass the original parent downstream so remaining extractors (CLIP, time, file) still run
                retrievable
            }
    }
}
