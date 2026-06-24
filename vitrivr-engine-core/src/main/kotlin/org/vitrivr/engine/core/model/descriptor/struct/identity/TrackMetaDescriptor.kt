package org.vitrivr.engine.core.model.descriptor.struct.identity

import org.vitrivr.engine.core.model.descriptor.Attribute
import org.vitrivr.engine.core.model.descriptor.AttributeName
import org.vitrivr.engine.core.model.descriptor.DescriptorId
import org.vitrivr.engine.core.model.descriptor.struct.StructDescriptor
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.core.model.retrievable.RetrievableId
import org.vitrivr.engine.core.model.types.Type
import org.vitrivr.engine.core.model.types.Value

/**
 * Struct descriptor holding per-track metadata: source video, frame/time range, and the number
 * of FACE_DETECTION members associated with the track.
 */
class TrackMetaDescriptor(
    override val id: DescriptorId,
    override val retrievableId: RetrievableId?,
    values: Map<AttributeName, Value<*>?>,
    override val field: Schema.Field<*, TrackMetaDescriptor>? = null,
) : StructDescriptor<TrackMetaDescriptor>(id, retrievableId, SCHEMA, values, field) {
    companion object {
        const val SOURCE_ID_FIELD = "source_id"
        const val START_NS_FIELD = "start_ns"
        const val END_NS_FIELD = "end_ns"
        const val DETECTION_COUNT_FIELD = "detection_count"

        val SCHEMA = listOf(
            Attribute(SOURCE_ID_FIELD, Type.String),
            Attribute(START_NS_FIELD, Type.Long),
            Attribute(END_NS_FIELD, Type.Long),
            Attribute(DETECTION_COUNT_FIELD, Type.Int),
        )
    }

    constructor(
        id: DescriptorId,
        retrievableId: RetrievableId?,
        sourceId: String,
        startNs: Long,
        endNs: Long,
        detectionCount: Int,
        field: Schema.Field<*, TrackMetaDescriptor>? = null,
    ) : this(
        id, retrievableId,
        mapOf(
            SOURCE_ID_FIELD to Value.String(sourceId),
            START_NS_FIELD to Value.Long(startNs),
            END_NS_FIELD to Value.Long(endNs),
            DETECTION_COUNT_FIELD to Value.Int(detectionCount),
        ),
        field,
    )

    override fun copy(id: DescriptorId, retrievableId: RetrievableId?, field: Schema.Field<*, TrackMetaDescriptor>?) =
        TrackMetaDescriptor(id, retrievableId, HashMap(this.values), field)
}
