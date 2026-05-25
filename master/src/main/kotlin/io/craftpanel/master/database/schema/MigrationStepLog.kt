package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

object MigrationStepLog : Table("migration_step_log") {
    val id = uuid("id").autoGenerate()
    val migrationId = uuid("migration_id").references(ServerMigrations.id)
    val stepNumber = integer("step_number")
    val description = varchar("description", 255)
    val status = varchar("status", 10)  // PENDING|RUNNING|SUCCESS|FAILED
    val startedAt = datetime("started_at").nullable()
    val completedAt = datetime("completed_at").nullable()
    val errorMessage = text("error_message").nullable()

    override val primaryKey = PrimaryKey(id)
}
