package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.MigrationStepLog
import io.craftpanel.master.service.repo.MigrationStepRow
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class MigrationStepEntity(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<MigrationStepEntity>(MigrationStepLog)

    var migrationId by MigrationStepLog.migrationId
    var stepNumber by MigrationStepLog.stepNumber
    var description by MigrationStepLog.description
    var status by MigrationStepLog.status
    var startedAt by MigrationStepLog.startedAt
    var completedAt by MigrationStepLog.completedAt
    var errorMessage by MigrationStepLog.errorMessage

    fun toMigrationStepRow() = MigrationStepRow(
        id = id.value,
        migrationId = migrationId.value,
        stepNumber = stepNumber,
        description = description,
        status = status,
        startedAt = startedAt?.toUtcString(),
        completedAt = completedAt?.toUtcString(),
        errorMessage = errorMessage
    )
}
