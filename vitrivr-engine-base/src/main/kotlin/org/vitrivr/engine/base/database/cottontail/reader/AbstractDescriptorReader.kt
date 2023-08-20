package org.vitrivr.engine.base.database.cottontail.reader

import io.grpc.StatusException
import org.vitrivr.cottontail.client.language.basics.expression.Column
import org.vitrivr.cottontail.client.language.basics.expression.List
import org.vitrivr.cottontail.client.language.basics.expression.Literal
import org.vitrivr.cottontail.client.language.basics.predicate.Compare
import org.vitrivr.cottontail.core.database.Name
import org.vitrivr.cottontail.core.tuple.Tuple
import org.vitrivr.cottontail.core.values.StringValue
import org.vitrivr.engine.base.database.cottontail.CottontailConnection
import org.vitrivr.engine.core.database.descriptor.DescriptorReader
import org.vitrivr.engine.core.model.database.descriptor.Descriptor
import org.vitrivr.engine.core.model.database.descriptor.vector.FloatVectorDescriptor
import org.vitrivr.engine.core.operators.Describer
import java.util.*

/**
 * An abstract implementation of a [DescriptorReader] for Cottontail DB.
 *
 * @author Ralph Gasser
 * @version 1.0.0
 */
abstract class AbstractDescriptorReader<T: Descriptor>(final override val describer: Describer<T>, protected val connection: CottontailConnection): DescriptorReader<T> {

    companion object {
        const val ID_COLUMN_NAME = "id"
    }

    /** The [Name.EntityName] used by this [FloatVectorDescriptor]. */
    protected val entityName: Name.EntityName = this.connection.schemaName.entity("${CottontailConnection.DESCRIPTOR_ENTITY_PREFIX}_${this.describer.instanceName.lowercase()}")

    /**
     * Returns a single [Descriptor]s of type [T] that has the provided [UUID].
     *
     * @return [Sequence] of all [Descriptor]s.
     */
    override fun get(id: UUID): T? {
        val query = org.vitrivr.cottontail.client.language.dql.Query(this.entityName)
            .where(Compare(Column(this.entityName.column(ID_COLUMN_NAME)), Compare.Operator.EQUAL, Literal(id.toString())))
        return try {
            val result = this.connection.client.query(query)
            val ret = if (result.hasNext()) {
                this.tupleToDescriptor(result.next())
            } else {
                null
            }
            result.close()
            ret
        } catch (e: StatusException) {
            /* TODO: Log. */
            null
        }
    }

    /**
     * Returns all [Descriptor]s of type [T] as a [Sequence].
     *
     * @return [Sequence] of all [Descriptor]s.
     */
    override fun getAll(): Sequence<T> {
        val query = org.vitrivr.cottontail.client.language.dql.Query(this.entityName)
        return try {
            val result = this.connection.client.query(query)
            result.asSequence().map { this.tupleToDescriptor(it) }
        } catch (e: StatusException) {
            /* TODO: Log. */
            emptySequence()
        }
    }

    /**
     * Returns all [Descriptor]s of type [T] that are contained in the provided [Iterable] of UUIDs as a [Sequence].
     *
     * @return [Sequence] of all [Descriptor]s.
     */
    override fun getAll(ids: Iterable<UUID>): Sequence<T> {
        val query = org.vitrivr.cottontail.client.language.dql.Query(this.entityName)
            .where(Compare(Column(this.entityName.column(ID_COLUMN_NAME)), Compare.Operator.IN, List(ids.map { StringValue(it.toString()) }.toTypedArray())))
        return try {
            val result = this.connection.client.query(query)
            result.asSequence().map { this.tupleToDescriptor(it) }
        } catch (e: StatusException) {
            /* TODO: Log. */
            emptySequence()
        }
    }

    /**
     * Returns the number of [Descriptor]s contained in the entity managed by this [AbstractDescriptorReader]
     *
     * @return [Sequence] of all [Descriptor]s.
     */
    override fun count(): Long {
        val query = org.vitrivr.cottontail.client.language.dql.Query(this.entityName).count()
        return try {
            val result = this.connection.client.query(query)
            val ret = if (result.hasNext()) {
                result.next().asLong(0) ?: 0L
            } else {
                0L
            }
            result.close()
            ret
        } catch (e: StatusException) {
            /* TODO: Log. */
            0L
        }
    }

    /**
     * Converts a [Tuple] to a [Descriptor] of type [T].
     *
     * @param tuple The [Tuple] to convert.
     * @return [Descriptor] of type [T]
     */
    protected abstract fun tupleToDescriptor(tuple: Tuple): T
}