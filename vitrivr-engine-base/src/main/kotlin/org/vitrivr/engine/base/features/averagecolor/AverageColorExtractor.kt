package org.vitrivr.engine.base.features.averagecolor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.vitrivr.engine.core.model.color.MutableRGBFloatColorContainer
import org.vitrivr.engine.core.model.color.RGBByteColorContainer
import org.vitrivr.engine.core.model.color.RGBFloatColorContainer
import org.vitrivr.engine.core.model.content.ImageContent
import org.vitrivr.engine.core.model.database.descriptor.vector.FloatVectorDescriptor
import org.vitrivr.engine.core.model.database.retrievable.IngestedRetrievable
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.core.operators.Operator
import org.vitrivr.engine.core.operators.derive.impl.AverageImageContentDeriver
import org.vitrivr.engine.core.operators.ingest.Extractor
import org.vitrivr.engine.core.util.extension.getRGBArray
import java.util.*

/**
 *
 * @author Ralph Gasser
 * @version 1.0.0
 */
class AverageColorExtractor(
    override val field: Schema.Field<FloatVectorDescriptor>,
    override val input: Operator<IngestedRetrievable>,
    override val persisting: Boolean = true,
) : Extractor<FloatVectorDescriptor> {

    /** The [AverageColorExtractor] implements the [AverageColor] analyser. */
    override val analyser: AverageColor = AverageColor()

    /** */
    override fun toFlow(): Flow<IngestedRetrievable> {
        val writer = if (this.persisting) {
            this.field.getWriter()
        } else {
            null
        }
        return this.input.toFlow().map { retrievable: IngestedRetrievable ->
            if (retrievable.content.any { c -> c is ImageContent }) {
                val averageImage = retrievable.getDerivedContent(AverageImageContentDeriver.derivateName) as? ImageContent
                if (averageImage != null) {
                    val color = MutableRGBFloatColorContainer()
                    var counter = 0
                    averageImage.image.getRGBArray().forEach { c ->
                        color += RGBByteColorContainer.fromRGB(c)
                        ++counter
                    }

                    /* Generate descriptor. */
                    val averageColor = RGBFloatColorContainer(color.red / counter, color.green / counter, color.blue / counter)
                    val descriptor = FloatVectorDescriptor(UUID.randomUUID(), retrievable.id, this.persisting, averageColor.toList())

                    /* Persist descriptor and attach it to retrievable. */
                    writer?.add(descriptor)
                    retrievable.descriptors.add(descriptor)
                }
            }
            retrievable
        }
    }
}