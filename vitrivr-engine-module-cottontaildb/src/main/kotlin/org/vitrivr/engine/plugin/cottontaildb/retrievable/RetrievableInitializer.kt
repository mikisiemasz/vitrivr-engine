package org.vitrivr.engine.plugin.cottontaildb.retrievable

import io.grpc.StatusRuntimeException
import org.vitrivr.cottontail.client.language.ddl.*
import org.vitrivr.cottontail.core.database.Name
import org.vitrivr.cottontail.core.types.Types
import org.vitrivr.cottontail.grpc.CottontailGrpc
import org.vitrivr.engine.core.database.retrievable.RetrievableInitializer
import org.vitrivr.engine.core.model.retrievable.Retrievable
import org.vitrivr.engine.plugin.cottontaildb.*

/**
 * A [RetrievableInitializer] implementation for Cottontail DB.
 *
 * @author Ralph Gasser
 * @version 1.0.0
 */
internal class RetrievableInitializer(private val connection: CottontailConnection) : RetrievableInitializer {

    /** The [Name.EntityName] for this [RetrievableInitializer]. */
    private val entityName: Name.EntityName = Name.EntityName.create(this.connection.schemaName, RETRIEVABLE_ENTITY_NAME)

    /** The [Name.EntityName] of the relationship entity. */
    private val relationshipEntityName: Name.EntityName = Name.EntityName.create(this.connection.schemaName, RELATIONSHIP_ENTITY_NAME)

    /**
     * Initializes the entity that is used to store [Retrievable]s in Cottontail DB.
     */
    override fun initialize() {
        /* Create schema. */
        val createSchema = CreateSchema(this.entityName.schema).ifNotExists()
        try {
            this.connection.client.create(createSchema)
        } catch (e: StatusRuntimeException) {
            LOGGER.error(e) { "Failed to initialize entity ${this.entityName} due to exception." }
        }

        try {
            /* Create retrievable entity. */
            this.connection.client.create(
                CreateEntity(this.entityName)
                    .column(Name.ColumnName.create(RETRIEVABLE_ID_COLUMN_NAME), Types.Uuid, nullable = false, primaryKey = true, autoIncrement = false)
                    .column(Name.ColumnName.create(RETRIEVABLE_TYPE_COLUMN_NAME), Types.String, nullable = true, primaryKey = false, autoIncrement = false)
                    .ifNotExists()
            )

            /* Create index on retrievable entity. */
            this.connection.client.create(
                CreateIndex(this.entityName, CottontailGrpc.IndexType.BTREE)
                    .column(this.entityName.column(RETRIEVABLE_TYPE_COLUMN_NAME))
            )
        } catch (e: StatusRuntimeException) {
            LOGGER.error(e) { "Failed to initialize entity ${this.entityName} due to exception." }
        }

        try {
            /* Create relationship entity. */
            this.connection.client.create(
                CreateEntity(this.relationshipEntityName)
                    .column(Name.ColumnName.create(SUBJECT_ID_COLUMN_NAME), Types.Uuid, nullable = false, primaryKey = false, autoIncrement = false)
                    .column(Name.ColumnName.create(PREDICATE_COLUMN_NAME), Types.String, nullable = false, primaryKey = false, autoIncrement = false)
                    .column(Name.ColumnName.create(OBJECT_ID_COLUMN_NAME), Types.Uuid, nullable = false, primaryKey = false, autoIncrement = false)
                    .ifNotExists()
            )

            /* Create index on relationship entity. */
            this.connection.client.create(
                CreateIndex(this.relationshipEntityName, CottontailGrpc.IndexType.BTREE)
                    .column(this.relationshipEntityName.column(SUBJECT_ID_COLUMN_NAME))
            )
            this.connection.client.create(
                CreateIndex(this.relationshipEntityName, CottontailGrpc.IndexType.BTREE)
                    .column(this.relationshipEntityName.column(OBJECT_ID_COLUMN_NAME))
            )
            this.connection.client.create(
                CreateIndex(this.relationshipEntityName, CottontailGrpc.IndexType.BTREE)
                    .column(this.relationshipEntityName.column(PREDICATE_COLUMN_NAME))
            )
        } catch (e: StatusRuntimeException) {
            LOGGER.error(e) { "Failed to initialize entity ${this.entityName} due to exception." }
        }
    }

    /**
     * De-initializes the entities that is used to store [Retrievable]s in Cottontail DB.
     */
    override fun deinitialize() {
        try {
            this.connection.client.drop(DropEntity(this.entityName))
        } catch (e: StatusRuntimeException) {
            if (e.status.code != io.grpc.Status.Code.NOT_FOUND) {
                LOGGER.error(e) { "Failed to initialize entity ${this.entityName} due to exception." }
            }
        }

        try {
            this.connection.client.drop(DropEntity(this.relationshipEntityName))
        } catch (e: StatusRuntimeException) {
            if (e.status.code != io.grpc.Status.Code.NOT_FOUND) {
                LOGGER.error(e) { "Failed to initialize entity '${this.entityName}' due to exception." }
            }
        }
    }

    /**
     * Checks if the schema for this [RetrievableInitializer] has been properly initialized.
     *
     * @return True if entity has been initialized, false otherwise.
     */
    override fun isInitialized(): Boolean = try {
        this.connection.client.list(ListEntities(this.entityName.schema)).asSequence().any {
            Name.EntityName.parse(it.asString(0)!!) == this.entityName
        }
    } catch (e: StatusRuntimeException) {
        false
    }

    /**
     * Truncates the entity that is used to store [Retrievable]s in Cottontail DB.
     */
    override fun truncate() {
        val truncate = TruncateEntity(this.entityName)
        try {
            this.connection.client.truncate(truncate)
        } catch (e: StatusRuntimeException) {
            LOGGER.error(e) { "Failed to truncate entity '${this.entityName}' due to exception." }
        }
    }
}