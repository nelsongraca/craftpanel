package io.craftpanel.master.service.repo

import io.craftpanel.master.domain.BackupStatus
import io.craftpanel.master.domain.BackupTrigger
import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

class FakeBackupRepository(private val state: FakeRepositories) : BackupRepository {

    override fun listBackups(serverId: Uuid): List<BackupRow> = state.backups.values.filter { it.serverId == serverId }
        .map { it.toRow() }

    override fun findBackupById(id: Uuid): BackupRow? = state.backups[id]?.toRow()

    override fun createBackup(serverId: Uuid, nodeId: Uuid, trigger: BackupTrigger): BackupRow {
        val id = Uuid.random()
        val b = FakeServerRepository.MutableBackup(id, serverId, nodeId, trigger.name)
        state.backups[id] = b
        return b.toRow()
    }

    override fun updateBackupStatus(id: Uuid, status: BackupStatus, filePath: String?, sizeBytes: Long?, errorMessage: String?, completedAt: Instant?) {
        state.backups[id]?.let {
            it.status = status.name
            if (filePath != null) it.filePath = filePath
            if (sizeBytes != null) it.sizeBytes = sizeBytes
            if (errorMessage != null) it.errorMessage = errorMessage
            if (completedAt != null) it.completedAt = completedAt.toString()
        }
    }

    override fun countCompletedBackups(serverId: Uuid): Int = state.backups.values.count { it.serverId == serverId && it.status == BackupStatus.COMPLETED.name }

    override fun deleteBackup(id: Uuid) {
        state.backups.remove(id)
    }

    override fun deleteBackupsForServer(serverId: Uuid) {
        state.backups.values.removeAll { it.serverId == serverId }
    }

    override fun failBackupsForNode(nodeId: Uuid) {
        state.backups.values.filter { it.nodeId == nodeId && it.status == BackupStatus.IN_PROGRESS.name }
            .forEach { it.status = BackupStatus.FAILED.name }
    }

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
