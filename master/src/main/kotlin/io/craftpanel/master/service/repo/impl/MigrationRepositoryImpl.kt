package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.schema.*
import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class MigrationRepositoryImpl : MigrationRepository {

    override fun findActiveMigration(serverId: Uuid): MigrationRow? = transaction {
        ServerMigrations.selectAll()
            .where {
                (ServerMigrations.serverId eq serverId) and
                    (ServerMigrations.status inList listOf(MigrationStatus.PENDING.name, MigrationStatus.RUNNING.name, MigrationStatus.SYNCING.name, MigrationStatus.CUTTING_OVER.name))
            }
            .firstOrNull()
            ?.toMigrationRow()
    }

    override fun listMigrations(serverId: Uuid): List<MigrationRow> = transaction {
        ServerMigrations.selectAll()
            .where { ServerMigrations.serverId eq serverId }
            .orderBy(ServerMigrations.createdAt, SortOrder.DESC)
            .map { it.toMigrationRow() }
    }

    override fun findMigrationById(id: Uuid): MigrationRow? = transaction {
        ServerMigrations.selectAll()
            .where { ServerMigrations.id eq id }
            .firstOrNull()
            ?.toMigrationRow()
    }

    override fun listMigrationSteps(migrationId: Uuid): List<MigrationStepRow> = transaction {
        MigrationStepLog.selectAll()
            .where { MigrationStepLog.migrationId eq migrationId }
            .orderBy(MigrationStepLog.stepNumber, SortOrder.ASC)
            .map { it.toMigrationStepRow() }
    }
}

private fun ResultRow.toMigrationRow() = MigrationRow(
    id = this[ServerMigrations.id].value,
    serverId = this[ServerMigrations.serverId].value,
    sourceNodeId = this[ServerMigrations.sourceNodeId].value,
    targetNodeId = this[ServerMigrations.targetNodeId].value,
    status = this[ServerMigrations.status],
    createdAt = this[ServerMigrations.createdAt].toUtcString(),
    completedAt = this[ServerMigrations.completedAt]?.toUtcString()
)

private fun ResultRow.toMigrationStepRow() = MigrationStepRow(
    id = this[MigrationStepLog.id].value,
    migrationId = this[MigrationStepLog.migrationId].value,
    stepNumber = this[MigrationStepLog.stepNumber],
    description = this[MigrationStepLog.description],
    status = this[MigrationStepLog.status],
    startedAt = this[MigrationStepLog.startedAt]?.toUtcString(),
    completedAt = this[MigrationStepLog.completedAt]?.toUtcString(),
    errorMessage = this[MigrationStepLog.errorMessage]
)