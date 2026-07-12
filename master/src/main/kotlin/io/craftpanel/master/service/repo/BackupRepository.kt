package io.craftpanel.master.service.repo

import io.craftpanel.master.domain.BackupStatus
import io.craftpanel.master.domain.BackupTrigger
import kotlin.uuid.Uuid

data class BackupRow(
    val id: Uuid,
    val serverId: Uuid,
    val nodeId: Uuid,
    val trigger: String,
    val status: String,
    val filePath: String?,
    val sizeBytes: Long?,
    val errorMessage: String?,
    val createdAt: String,
    val completedAt: String?
)

interface BackupRepository {

    fun listBackups(serverId: Uuid): List<BackupRow>
    fun findBackupById(id: Uuid): BackupRow?
    fun createBackup(serverId: Uuid, nodeId: Uuid, trigger: BackupTrigger): BackupRow
    fun updateBackupStatus(id: Uuid, status: BackupStatus, filePath: String?, sizeBytes: Long?, errorMessage: String?, completedAt: kotlin.time.Instant?)
    fun countCompletedBackups(serverId: Uuid): Int
    fun deleteBackup(id: Uuid)
    fun deleteBackupsForServer(serverId: Uuid)
    fun failBackupsForNode(nodeId: Uuid)
    fun findOldestCompletedBackups(serverId: Uuid, keepCount: Int): List<BackupRow>
}
