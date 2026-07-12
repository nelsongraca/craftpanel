package io.craftpanel.master.service.repo

import io.craftpanel.master.database.schema.Backups
import io.craftpanel.master.domain.BackupStatus
import io.craftpanel.master.domain.BackupTrigger
import io.craftpanel.master.util.toUtcString
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class BackupRepositoryImpl : BackupRepository {

    override fun listBackups(serverId: Uuid): List<BackupRow> = transaction {
        Backups.selectAll()
            .where { Backups.serverId eq serverId }
            .orderBy(Backups.createdAt, SortOrder.DESC)
            .map { it.toBackupRow() }
    }

    override fun findBackupById(id: Uuid): BackupRow? = transaction {
        Backups.selectAll()
            .where { Backups.id eq id }
            .firstOrNull()
            ?.toBackupRow()
    }

    override fun createBackup(serverId: Uuid, nodeId: Uuid, trigger: BackupTrigger): BackupRow = transaction {
        val id = Backups.insert {
            it[Backups.serverId] = serverId
            it[Backups.nodeId] = nodeId
            it[Backups.trigger] = trigger.name
            it[Backups.status] = BackupStatus.IN_PROGRESS.name
        }[Backups.id]
        Backups.selectAll()
            .where { Backups.id eq id }
            .first()
            .toBackupRow()
    }

    override fun updateBackupStatus(id: Uuid, status: BackupStatus, filePath: String?, sizeBytes: Long?, errorMessage: String?, completedAt: Instant?) {
        transaction {
            Backups.update({ Backups.id eq id }) {
                it[Backups.status] = status.name
                if (filePath != null) it[Backups.filePath] = filePath
                if (sizeBytes != null) it[Backups.sizeBytes] = sizeBytes
                if (errorMessage != null) it[Backups.errorMessage] = errorMessage
                if (completedAt != null) it[Backups.completedAt] = completedAt.toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun countCompletedBackups(serverId: Uuid): Int = transaction {
        Backups.selectAll()
            .where { (Backups.serverId eq serverId) and (Backups.status eq BackupStatus.COMPLETED.name) }
            .toList()
            .size
    }

    override fun deleteBackup(id: Uuid) {
        transaction { Backups.deleteWhere { Backups.id eq id } }
    }

    override fun deleteBackupsForServer(serverId: Uuid) {
        transaction { Backups.deleteWhere { Backups.serverId eq serverId } }
    }

    override fun failBackupsForNode(nodeId: Uuid) {
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)
        transaction {
            Backups.update({ (Backups.nodeId eq nodeId) and (Backups.status eq BackupStatus.IN_PROGRESS.name) }) {
                it[Backups.status] = BackupStatus.FAILED.name
                it[Backups.errorMessage] = "Node went offline during backup"
                it[Backups.completedAt] = now
            }
        }
    }

    override fun findOldestCompletedBackups(serverId: Uuid, keepCount: Int): List<BackupRow> = transaction {
        val rows = Backups.selectAll()
            .where { (Backups.serverId eq serverId) and (Backups.status eq BackupStatus.COMPLETED.name) }
            .orderBy(Backups.createdAt to SortOrder.ASC)
            .toList()
        if (rows.size < keepCount) {
            emptyList()
        } else {
            rows.dropLast(keepCount - 1)
                .map { it.toBackupRow() }
        }
    }
}

private fun ResultRow.toBackupRow() = BackupRow(
    id = this[Backups.id],
    serverId = this[Backups.serverId],
    nodeId = this[Backups.nodeId],
    trigger = this[Backups.trigger],
    status = this[Backups.status],
    filePath = this[Backups.filePath],
    sizeBytes = this[Backups.sizeBytes],
    errorMessage = this[Backups.errorMessage],
    createdAt = this[Backups.createdAt].toUtcString(),
    completedAt = this[Backups.completedAt]?.toString()
)
