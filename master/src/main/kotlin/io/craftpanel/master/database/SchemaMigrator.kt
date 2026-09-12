package io.craftpanel.master.database

import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.Table
import org.slf4j.LoggerFactory

object SchemaMigrator {

    private val log = LoggerFactory.getLogger("SchemaMigrator")

    fun migrate(vararg tables: Table) {
        val sql = MigrationUtils.statementsRequiredForDatabaseMigration(*tables)
        val destructive = sql.filter { it.uppercase().trimStart().startsWith("DROP") }
        if (destructive.isNotEmpty()) {
            log.warn("=== DESTRUCTIVE SCHEMA CHANGES DETECTED ===")
            destructive.forEach { log.warn("  $it") }
            log.warn("Backup your database before proceeding — see deployment docs")
        }
        transaction {
            sql.forEach { exec(it) }
        }
    }
}