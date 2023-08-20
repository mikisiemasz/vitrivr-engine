package org.vitrivr.engine.base.database.cottontail.writer

import io.grpc.StatusException
import org.vitrivr.cottontail.client.language.dml.BatchInsert
import org.vitrivr.cottontail.client.language.dml.Insert
import org.vitrivr.cottontail.core.database.Name
import org.vitrivr.engine.base.database.cottontail.CottontailConnection
import org.vitrivr.engine.core.database.retrievable.RetrievableWriter
import org.vitrivr.engine.core.model.database.retrievable.Retrievable

/**
 * A [RetrievableWriter] implementation for Cottontail DB.
 *
 * @author Ralph Gasser
 * @version 1.0.0
 */
internal class RetrievableWriter(private val connection: CottontailConnection): RetrievableWriter {

    /** The [Name.EntityName]*/
    private val entityName: Name.EntityName = this.connection.schemaName.entity(CottontailConnection.RETRIEVABLE_ENTITY_NAME)

    /**
     * Adds a new [Retrievable] to the database using this [RetrievableWriter] instance.
     *
     * @param item [Retrievable] to add.
     */
    override fun add(item: Retrievable): Boolean {
        val insert = Insert(this.entityName).any("id", item.id)
        return try {
            this.connection.client.insert(insert)
            true
        } catch (e: StatusException) {
            /* TODO: Log */
            false
        }
    }

    /**
     * Adds new [Retrievable]s to the database using this [RetrievableWriter] instance.
     *
     * @param items An [Iterable] of [Retrievable]s to add.
     */
    override fun addAll(items: Iterable<Retrievable>): Boolean {
        /* Prepare insert query. */
        val insert = BatchInsert(this.entityName).columns("id")
        for (item in items) {
            insert.any(item)
        }

        /* Insert values. */
        return try {
            this.connection.client.insert(insert)
            true
        } catch (e: StatusException) {
            /* TODO: Log */
            false
        }
    }

    override fun update(item: Retrievable): Boolean {
        TODO("Not yet implemented")
    }

    override fun delete(item: Retrievable): Boolean {
        TODO("Not yet implemented")
    }
}