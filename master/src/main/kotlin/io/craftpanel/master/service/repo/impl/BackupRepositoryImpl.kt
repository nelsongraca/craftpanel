package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.schema.Backups
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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

    override fun countCompletedBackups(serverId: Uuid): Int = transaction {
        Backups.selectAll()
            .where { (Backups.serverId eq serverId) and (Backups.status eq "COMPLETED") }
            .toList()
            .size
    }

    override fun findOldestCompletedBackups(serverId: Uuid, keepCount: Int): List<BackupRow> = transaction {
        val rows = Backups.selectAll()
            .where { (Backups.serverId eq serverId) and (Backups.status eq "COMPLETED") }
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
    id = this[Backups.id].value,
    serverId = this[Backups.serverId].value,
    nodeId = this[Backups.nodeId].value,
    trigger = this[Backups.trigger],
    status = this[Backups.status],
    filePath = this[Backups.filePath],
    sizeBytes = this[Backups.sizeBytes],
    errorMessage = this[Backups.errorMessage],
    createdAt = this[Backups.createdAt].toUtcString(),
    completedAt = this[Backups.completedAt]?.toUtcString()
)