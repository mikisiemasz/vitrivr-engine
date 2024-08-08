package org.vitrivr.engine.base.features.external.implementations.dense

import org.vitrivr.engine.base.features.external.api.AsrApi
import org.vitrivr.engine.base.features.external.api.ImageEmbeddingApi
import org.vitrivr.engine.base.features.external.api.TextEmbeddingApi
import org.vitrivr.engine.base.features.external.common.ExternalFesAnalyser
import org.vitrivr.engine.base.features.external.common.FesExtractor
import org.vitrivr.engine.core.model.content.element.ContentElement
import org.vitrivr.engine.core.model.content.element.ImageContent
import org.vitrivr.engine.core.model.content.element.TextContent
import org.vitrivr.engine.core.model.descriptor.Descriptor
import org.vitrivr.engine.core.model.descriptor.vector.FloatVectorDescriptor
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.core.model.retrievable.Retrievable
import org.vitrivr.engine.core.operators.Operator
import java.util.*

/**
 *
 * @author Ralph Gasser
 * @version 1.0.0
 */
class DenseEmbeddingExtractor(
    input: Operator<Retrievable>,
    field: Schema.Field<ContentElement<*>, FloatVectorDescriptor>?,
    analyser: ExternalFesAnalyser<ContentElement<*>, FloatVectorDescriptor>,
    parameters: Map<String, String>
) : FesExtractor<ContentElement<*>, FloatVectorDescriptor>(input, field, analyser, parameters) {
    /** The [AsrApi] used to perform extraction with. */
    private val textApi by lazy { TextEmbeddingApi(this.host, model, this.timeoutMs, this.pollingIntervalMs, this.retries) }

    /** The [AsrApi] used to perform extraction with. */
    private val imageApi by lazy { ImageEmbeddingApi(this.host, model, this.timeoutMs, this.pollingIntervalMs, this.retries) }


    /**
     * Internal method to perform extraction on [Retrievable].
     **
     * @param retrievables The [Retrievable]s to process.
     * @return List of resulting [Descriptor]s grouped by [Retrievable].
     */
    override fun extract(retrievables: List<Retrievable>): List<List<FloatVectorDescriptor>> {
        val content = retrievables.flatMap { this.filterContent(it) }
        val textContent = content.mapIndexed { index, contentElement -> if (contentElement is TextContent) index to contentElement else null }.filterNotNull().toMap()
        val imageContent = content.mapIndexed { index, contentElement -> if (contentElement is ImageContent) index to contentElement else null }.filterNotNull().toMap()

        val textResults: List<FloatVectorDescriptor> = if (textContent.isNotEmpty()) {
            this.textApi.analyseBatched(textContent.map { it.value })
                .map { FloatVectorDescriptor(UUID.randomUUID(), null, it, this.field) }
        } else {
            emptyList()
        }

        val imageResults: List<FloatVectorDescriptor> = if (imageContent.isNotEmpty()) {
            this.imageApi.analyseBatched(imageContent.map { it.value })
                .map { FloatVectorDescriptor(UUID.randomUUID(), null, it, this.field) }
        } else {
            emptyList()
        }


        val textResultMap = textContent.keys.zip(textResults).toMap()
        val imageResultMap = imageContent.keys.zip(imageResults).toMap()

        return retrievables.indices.map { index ->
            val descriptors = mutableListOf<FloatVectorDescriptor>()
            textResultMap[index]?.let {
                it.retrievableId = retrievables[index].id
                descriptors.add(it)
            }
            imageResultMap[index]?.let {
                it.retrievableId = retrievables[index].id
                descriptors.add(it)
            }
            descriptors
        }
    }


}