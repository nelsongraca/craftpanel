package io.craftpanel.master.service.repo

import io.craftpanel.master.database.schema.*
import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.domain.MigrationStepStatus
import io.craftpanel.master.util.toUtcString
import kotlinx.datetime.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
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

    override fun createMigration(serverId: Uuid, sourceNodeId: Uuid, targetNodeId: Uuid): MigrationRow = transaction {
        val id = ServerMigrations.insert {
            it[ServerMigrations.serverId] = serverId
            it[ServerMigrations.sourceNodeId] = sourceNodeId
            it[ServerMigrations.targetNodeId] = targetNodeId
            it[ServerMigrations.status] = MigrationStatus.PENDING.name
        }[ServerMigrations.id]
        ServerMigrations.selectAll()
            .where { ServerMigrations.id eq id }
            .first()
            .toMigrationRow()
    }

    override fun updateMigrationStatus(id: Uuid, status: MigrationStatus, completedAt: Instant?) {
        transaction {
            ServerMigrations.update({ ServerMigrations.id eq id }) {
                it[ServerMigrations.status] = status.name
                if (completedAt != null) it[ServerMigrations.completedAt] = completedAt.toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun failMigrationsForNode(nodeId: Uuid) {
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)
        transaction {
            ServerMigrations.update({
                ((ServerMigrations.sourceNodeId eq nodeId) or (ServerMigrations.targetNodeId eq nodeId)) and
                    (ServerMigrations.status inList listOf(MigrationStatus.PENDING.name, MigrationStatus.SYNCING.name, MigrationStatus.CUTTING_OVER.name))
            }) {
                it[ServerMigrations.status] = MigrationStatus.FAILED.name
                it[ServerMigrations.completedAt] = now
            }
        }
    }

    override fun failAllStuckMigrations() {
        transaction {
            ServerMigrations.update({
                ServerMigrations.status inList listOf(MigrationStatus.PENDING.name, MigrationStatus.SYNCING.name, MigrationStatus.CUTTING_OVER.name, MigrationStatus.RUNNING.name)
            }) {
                it[ServerMigrations.status] = MigrationStatus.FAILED.name
                it[ServerMigrations.completedAt] = Clock.System.now()
                    .toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun updateNodeId(id: Uuid, nodeId: Uuid) {
        transaction {
            Servers.update({ Servers.id eq id }) {
                it[Servers.nodeId] = nodeId
                it[Servers.updatedAt] = Clock.System.now()
                    .toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun updateMigrationHostPort(id: Uuid, hostPort: Int) {
        transaction { Servers.update({ Servers.id eq id }) { it[Servers.hostPort] = hostPort } }
    }

    override fun listMigrationSteps(migrationId: Uuid): List<MigrationStepRow> = transaction {
        MigrationStepLog.selectAll()
            .where { MigrationStepLog.migrationId eq migrationId }
            .orderBy(MigrationStepLog.stepNumber, SortOrder.ASC)
            .map { it.toMigrationStepRow() }
    }

    override fun createMigrationStep(migrationId: Uuid, stepNumber: Int, description: String): MigrationStepRow = transaction {
        val id = MigrationStepLog.insert {
            it[MigrationStepLog.migrationId] = migrationId
            it[MigrationStepLog.stepNumber] = stepNumber
            it[MigrationStepLog.description] = description
            it[MigrationStepLog.status] = MigrationStepStatus.PENDING.name
        }[MigrationStepLog.id]
        MigrationStepLog.selectAll()
            .where { MigrationStepLog.id eq id }
            .first()
            .toMigrationStepRow()
    }

    override fun updateMigrationStepStatus(id: Uuid, status: MigrationStepStatus, startedAt: Instant?, completedAt: Instant?, errorMessage: String?) {
        transaction {
            MigrationStepLog.update({ MigrationStepLog.id eq id }) {
                it[MigrationStepLog.status] = status.name
                if (startedAt != null) it[MigrationStepLog.startedAt] = startedAt.toLocalDateTime(TimeZone.UTC)
                if (completedAt != null) it[MigrationStepLog.completedAt] = completedAt.toLocalDateTime(TimeZone.UTC)
                if (errorMessage != null) it[MigrationStepLog.errorMessage] = errorMessage
            }
        }
    }

    override fun deleteMigrationStepsForServer(serverId: Uuid) {
        transaction {
            val migrationIds = ServerMigrations.selectAll()
                .where { ServerMigrations.serverId eq serverId }
                .map { it[ServerMigrations.id] }
            if (migrationIds.isNotEmpty()) {
                MigrationStepLog.deleteWhere { MigrationStepLog.migrationId inList migrationIds }
            }
        }
    }

    override fun deleteMigrationsForServer(serverId: Uuid) {
        transaction { ServerMigrations.deleteWhere { ServerMigrations.serverId eq serverId } }
    }
}

private fun ResultRow.toMigrationRow() = MigrationRow(
    id = this[ServerMigrations.id],
    serverId = this[ServerMigrations.serverId],
    sourceNodeId = this[ServerMigrations.sourceNodeId],
    targetNodeId = this[ServerMigrations.targetNodeId],
    status = this[ServerMigrations.status],
    createdAt = this[ServerMigrations.createdAt].toUtcString(),
    completedAt = this[ServerMigrations.completedAt]?.toUtcString()
)

private fun ResultRow.toMigrationStepRow() = MigrationStepRow(
    id = this[MigrationStepLog.id],
    migrationId = this[MigrationStepLog.migrationId],
    stepNumber = this[MigrationStepLog.stepNumber],
    description = this[MigrationStepLog.description],
    status = this[MigrationStepLog.status],
    startedAt = this[MigrationStepLog.startedAt]?.toString(),
    completedAt = this[MigrationStepLog.completedAt]?.toString(),
    errorMessage = this[MigrationStepLog.errorMessage]
)
