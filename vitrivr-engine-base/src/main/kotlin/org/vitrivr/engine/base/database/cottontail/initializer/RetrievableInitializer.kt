package org.vitrivr.engine.base.database.cottontail.initializer

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.StatusException
import org.vitrivr.cottontail.client.language.ddl.CreateEntity
import org.vitrivr.cottontail.client.language.ddl.CreateSchema
import org.vitrivr.cottontail.client.language.ddl.TruncateEntity
import org.vitrivr.cottontail.core.database.Name
import org.vitrivr.cottontail.core.types.Types
import org.vitrivr.engine.base.database.cottontail.CottontailConnection
import org.vitrivr.engine.base.database.cottontail.CottontailConnection.Companion.RETRIEVABLE_ENTITY_NAME
import org.vitrivr.engine.base.database.cottontail.CottontailConnection.Companion.RETRIEVABLE_ID_COLUMN_NAME
import org.vitrivr.engine.core.database.retrievable.RetrievableInitializer
import org.vitrivr.engine.core.model.database.retrievable.Retrievable

/** Defines [KLogger] of the class. */
private val logger: KLogger = KotlinLogging.logger {}

/**
 * A [RetrievableInitializer] implementation for Cottontail DB.
 *
 * @author Ralph Gasser
 * @version 1.0.0
 */
internal class RetrievableInitializer(private val connection: CottontailConnection): RetrievableInitializer {

    /** The [Name.EntityName] for this [RetrievableInitializer]. */
    private val entityName: Name.EntityName = Name.EntityName(this.connection.schemaName, RETRIEVABLE_ENTITY_NAME)

    /**
     * Initializes the entity that is used to store [Retrievable]s in Cottontail DB.
     */
    override fun initialize() {
        /* Create schema. */
        val createSchema = CreateSchema(this.entityName.schemaName).ifNotExists()
        try {
            this.connection.client.create(createSchema)
        } catch (e: StatusException) {
            logger.error(e) { "Failed to initialize entity ${this.entityName} due to exception." }
        }

        /* Create entity. */
        val createEntity = CreateEntity(this.entityName)
            .column(Name.ColumnName(RETRIEVABLE_ID_COLUMN_NAME), Types.String, nullable = false, primaryKey = true, autoIncrement = false)
            .column(Name.ColumnName("type"), Types.String, nullable = true, primaryKey = false, autoIncrement = false)
            .ifNotExists()

        try {
            this.connection.client.create(createEntity)
        } catch (e: StatusException) {
            logger.error(e) { "Failed to initialize entity ${this.entityName} due to exception." }
        }
    }

    /**
     * Truncates the entity that is used to store [Retrievable]s in Cottontail DB.
     */
    override fun truncate() {
        val truncate = TruncateEntity(this.entityName)
        try {
            this.connection.client.truncate(truncate)
        } catch (e: StatusException) {
            logger.error(e) { "Failed to truncate entity ${this.entityName} due to exception." }
        }
    }
}