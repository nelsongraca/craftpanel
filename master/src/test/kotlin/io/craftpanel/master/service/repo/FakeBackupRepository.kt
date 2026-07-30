package io.craftpanel.master.service.repo

import io.craftpanel.master.domain.BackupStatus
import kotlin.uuid.Uuid

class FakeBackupRepository(private val state: FakeRepositories) : BackupRepository {

    override fun listBackups(serverId: Uuid): List<BackupRow> = state.backups.values.filter { it.serverId == serverId }
        .map { it.toRow() }

    override fun findBackupById(id: Uuid): BackupRow? = state.backups[id]?.toRow()

    override fun countCompletedBackups(serverId: Uuid): Int = state.backups.values.count { it.serverId == serverId && it.status == BackupStatus.COMPLETED.name }

    override fun findOldestCompletedBackups(serverId: Uuid, keepCount: Int): List<BackupRow> {
        val completed = state.backups.values.filter { it.serverId == serverId && it.status == BackupStatus.COMPLETED.name }
            .sortedBy { it.createdAt }
        return if (completed.size <= keepCount) {
            emptyList()
        } else {
            completed.dropLast(keepCount)
                .map { it.toRow() }
        }
    }

    private fun FakeServerRepository.MutableBackup.toRow() = BackupRow(id, serverId, nodeId, trigger, status, filePath, sizeBytes, errorMessage, createdAt, completedAt)
}