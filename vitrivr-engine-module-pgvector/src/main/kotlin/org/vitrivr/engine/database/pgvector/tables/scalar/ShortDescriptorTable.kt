package org.vitrivr.engine.database.pgvector.tables.scalar

import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll
import org.vitrivr.engine.core.model.descriptor.scalar.ShortDescriptor
import org.vitrivr.engine.core.model.metamodel.Schema
import org.vitrivr.engine.core.model.query.basics.ComparisonOperator
import org.vitrivr.engine.core.model.query.bool.SimpleBooleanQuery
import org.vitrivr.engine.core.model.types.Value

/**
 * Table definition for the [ShortDescriptor] entity.
 *
 * @author Ralph Gasser
 * @version 1.0.0
 */
internal class ShortDescriptorTable(field: Schema.Field<*, ShortDescriptor>): AbstractScalarDescriptorTable<ShortDescriptor, Value.Short, Short>(field) {
    override val descriptor = short("descriptor")

    init {
        this.initializeIndexes()
    }

    /**
     * Converts a [ResultRow] to a [ShortDescriptor].
     *
     * @param row The [ResultRow] to convert.
     * @return The [ShortDescriptor] represented by the [ResultRow].
     */
    override fun rowToDescriptor(row: ResultRow) = ShortDescriptor(
        id = row[id].value,
        retrievableId = row[retrievableId].value,
        value = Value.Short(row[descriptor]),
        this.field
    )

    /**
     * Converts a [SimpleBooleanQuery] into a [Query] that can be executed against the database.
     *
     * @param query The [SimpleBooleanQuery] to convert.
     * @return The [Query] that can be executed against the database.
     */
    override fun parse(query: SimpleBooleanQuery<*>): Query = this.selectAll().where {
        val value = query.value.value as? Short ?: throw IllegalArgumentException("Failed to execute query on ${nameInDatabaseCase()}. Comparison value of wrong type.")
        when(query.comparison) {
            ComparisonOperator.EQ -> descriptor eq value
            ComparisonOperator.NEQ -> descriptor neq value
            ComparisonOperator.LE -> descriptor less value
            ComparisonOperator.GR -> descriptor greater value
            ComparisonOperator.LEQ -> descriptor lessEq value
            ComparisonOperator.GEQ -> descriptor greaterEq value
            else -> throw IllegalArgumentException("Unsupported comparison type: ${query.comparison}")
        }
    }
}