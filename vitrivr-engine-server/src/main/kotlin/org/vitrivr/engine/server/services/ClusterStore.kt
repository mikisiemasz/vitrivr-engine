package org.vitrivr.engine.server.services

import io.github.oshai.kotlinlogging.KotlinLogging
import org.vitrivr.engine.core.model.descriptor.scalar.StringDescriptor
import org.vitrivr.engine.core.model.descriptor.vector.FloatVectorDescriptor
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.core.model.types.Value
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Per-schema view over cluster centroid + label storage.
 *
 * Centroids and labels are persisted in the `clusterCentroid` and `clusterLabel` descriptor
 * fields (one descriptor per FACE_CLUSTER retrievable, keyed by retrievableId). For schemas
 * that pre-date these fields — or for clusters created before the migration — reads transparently
 * fall back to the legacy [ClusterStateStore] sidecar. Writes only go to the descriptor fields;
 * the sidecar is read-only during the transition and will be removed in cleanup.
 *
 * Centroid storage is "one descriptor per cluster": setting overwrites by first deleting any
 * existing descriptor for that retrievableId, then inserting the new one. Same for labels.
 */
class ClusterStore(
    private val schema: Schema,
    private val sidecar: ClusterStateStore,
) {

    @Suppress("UNCHECKED_CAST")
    private val centroidField: Schema.Field<*, FloatVectorDescriptor>? =
        schema[CENTROID_FIELD_NAME] as? Schema.Field<*, FloatVectorDescriptor>

    @Suppress("UNCHECKED_CAST")
    private val labelField: Schema.Field<*, StringDescriptor>? =
        schema[LABEL_FIELD_NAME] as? Schema.Field<*, StringDescriptor>

    init {
        if (centroidField == null) logger.warn { "[ClusterStore] Schema '${schema.name}' missing '$CENTROID_FIELD_NAME' field; centroids will only be readable from sidecar." }
        if (labelField == null) logger.warn { "[ClusterStore] Schema '${schema.name}' missing '$LABEL_FIELD_NAME' field; labels will only be readable from sidecar." }
    }

    /* ── centroid ─────────────────────────────────────────────────────────── */

    fun getCentroid(clusterId: UUID): FloatArray? {
        centroidField?.let { f ->
            f.getReader().getForRetrievable(clusterId).firstOrNull()?.let {
                return it.vector.value
            }
        }
        return sidecar.getCentroid(clusterId)
    }

    fun allCentroids(): Map<UUID, FloatArray> {
        val merged = HashMap<UUID, FloatArray>()
        /* Sidecar first so descriptor entries overwrite (descriptor is source of truth). */
        merged.putAll(sidecar.allCentroids())
        centroidField?.getReader()?.getAll()?.forEach { d ->
            d.retrievableId?.let { merged[it] = d.vector.value }
        }
        return merged
    }

    fun setCentroid(clusterId: UUID, centroid: FloatArray) {
        val f = centroidField ?: run {
            logger.warn { "[ClusterStore] No clusterCentroid field; centroid for $clusterId not persisted." }
            return
        }
        val writer = f.getWriter()
        f.getReader().getForRetrievable(clusterId).toList().forEach { writer.delete(it) }
        writer.add(
            FloatVectorDescriptor(
                id = UUID.randomUUID(),
                retrievableId = clusterId,
                vector = Value.FloatVector(centroid),
                field = f,
            )
        )
    }

    fun setCentroids(updates: Map<UUID, FloatArray>) {
        for ((id, vec) in updates) setCentroid(id, vec)
    }

    /* ── label ────────────────────────────────────────────────────────────── */

    fun getLabel(clusterId: UUID): String? {
        labelField?.let { f ->
            f.getReader().getForRetrievable(clusterId).firstOrNull()?.let {
                return it.value.value
            }
        }
        return sidecar.getLabel(clusterId)
    }

    fun allLabels(): Map<UUID, String> {
        val merged = HashMap<UUID, String>()
        merged.putAll(sidecar.allLabels())
        labelField?.getReader()?.getAll()?.forEach { d ->
            d.retrievableId?.let { merged[it] = d.value.value }
        }
        return merged
    }

    fun setLabel(clusterId: UUID, label: String?) {
        val f = labelField ?: run {
            logger.warn { "[ClusterStore] No clusterLabel field; label for $clusterId not persisted." }
            return
        }
        val writer = f.getWriter()
        f.getReader().getForRetrievable(clusterId).toList().forEach { writer.delete(it) }
        val trimmed = label?.trim()?.takeIf { it.isNotEmpty() } ?: return
        writer.add(
            StringDescriptor(
                id = UUID.randomUUID(),
                retrievableId = clusterId,
                value = Value.String(trimmed),
                field = f,
            )
        )
    }

    /* ── removal ──────────────────────────────────────────────────────────── */

    fun remove(clusterId: UUID) {
        centroidField?.let { f ->
            val w = f.getWriter()
            f.getReader().getForRetrievable(clusterId).toList().forEach { w.delete(it) }
        }
        labelField?.let { f ->
            val w = f.getWriter()
            f.getReader().getForRetrievable(clusterId).toList().forEach { w.delete(it) }
        }
        /* Also clear the sidecar so stale entries don't reappear via fallback reads. */
        sidecar.remove(clusterId)
    }

    companion object {
        const val CENTROID_FIELD_NAME = "clusterCentroid"
        const val LABEL_FIELD_NAME = "clusterLabel"

        fun forSchema(schema: Schema): ClusterStore =
            ClusterStore(schema, ClusterStateStore.forSchema(schema.name))
    }
}
