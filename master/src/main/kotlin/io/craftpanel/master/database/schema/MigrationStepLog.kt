package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.datetime.datetime

object MigrationStepLog : UuidTable("migration_step_log") {

    val migrationId = reference("migration_id", ServerMigrations, onDelete = ReferenceOption.CASCADE)
    val stepNumber = integer("step_number")
    val description = varchar("description", 255)
    val status = varchar("status", 10)  // PENDING|RUNNING|SUCCESS|FAILED
    val startedAt = datetime("started_at").nullable()
    val completedAt = datetime("completed_at").nullable()
    val errorMessage = text("error_message").nullable()
}
