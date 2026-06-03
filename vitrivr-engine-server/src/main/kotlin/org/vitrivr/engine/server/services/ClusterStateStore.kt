package org.vitrivr.engine.server.services

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private val logger = KotlinLogging.logger {}

/**
 * On-disk JSON store for state that lives alongside FACE_CLUSTER retrievables but is not
 * persisted in the engine database: per-cluster centroid embeddings and curator labels.
 *
 * One JSON file per schema, atomic write via temp-rename. Concurrency guarded by a process-local
 * RW lock; a per-schema instance is cached in [forSchema] so concurrent REST calls share the
 * same lock for a given schema.
 *
 * The base directory is resolved in this order:
 *   1. JVM system property `cluster.state.dir`  (set via `-Dcluster.state.dir=...` in the launcher)
 *   2. Env var `CLUSTER_STATE_DIR`
 *   3. Default: `cluster_state` (relative to engine CWD)
 *
 * The state file is then `<base>/<schemaName>.json`.
 */
@Serializable
data class ClusterStateFile(
    val labels: MutableMap<String, String> = mutableMapOf(),
    val centroids: MutableMap<String, List<Float>> = mutableMapOf(),
)

class ClusterStateStore(private val path: Path) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = ReentrantReadWriteLock()

    @Volatile private var state: ClusterStateFile = load()

    private fun load(): ClusterStateFile = try {
        if (Files.exists(path)) json.decodeFromString(ClusterStateFile.serializer(), Files.readString(path))
        else ClusterStateFile()
    } catch (e: Exception) {
        logger.error(e) { "Failed to read cluster state from $path — starting empty." }
        ClusterStateFile()
    }

    private fun persist(snapshot: ClusterStateFile) {
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.createDirectories(path.parent ?: Paths.get("."))
        Files.writeString(tmp, json.encodeToString(ClusterStateFile.serializer(), snapshot))
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    fun getLabel(clusterId: UUID): String? = lock.read { state.labels[clusterId.toString()] }

    fun allLabels(): Map<UUID, String> = lock.read {
        state.labels.mapNotNull { (k, v) -> runCatching { UUID.fromString(k) to v }.getOrNull() }.toMap()
    }

    fun setLabel(clusterId: UUID, label: String?) = lock.write {
        if (label.isNullOrBlank()) state.labels.remove(clusterId.toString())
        else state.labels[clusterId.toString()] = label
        persist(state)
    }

    fun getCentroid(clusterId: UUID): FloatArray? = lock.read {
        state.centroids[clusterId.toString()]?.toFloatArray()
    }

    fun allCentroids(): Map<UUID, FloatArray> = lock.read {
        state.centroids.mapNotNull { (k, v) ->
            runCatching { UUID.fromString(k) to v.toFloatArray() }.getOrNull()
        }.toMap()
    }

    fun setCentroid(clusterId: UUID, centroid: FloatArray) = lock.write {
        state.centroids[clusterId.toString()] = centroid.toList()
        persist(state)
    }

    fun setCentroids(updates: Map<UUID, FloatArray>) = lock.write {
        for ((id, vec) in updates) state.centroids[id.toString()] = vec.toList()
        persist(state)
    }

    /** Drop label + centroid for a cluster (e.g. after merge/split removes it). */
    fun remove(clusterId: UUID) = lock.write {
        state.labels.remove(clusterId.toString())
        state.centroids.remove(clusterId.toString())
        persist(state)
    }

    companion object {
        private val instances = ConcurrentHashMap<String, ClusterStateStore>()

        private fun baseDir(): Path = Paths.get(
            System.getProperty("cluster.state.dir")
                ?: System.getenv("CLUSTER_STATE_DIR")
                ?: "cluster_state"
        ).toAbsolutePath()

        /**
         * Returns the [ClusterStateStore] for the given schema, creating it on first access.
         * Subsequent calls for the same schema name return the same instance so writes share
         * the same RW lock and there is no race on the JSON file.
         */
        fun forSchema(schemaName: String): ClusterStateStore = instances.computeIfAbsent(schemaName) {
            ClusterStateStore(baseDir().resolve("$it.json"))
        }
    }
}
