package io.craftpanel.master.service.repo

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
    fun countCompletedBackups(serverId: Uuid): Int
    fun findOldestCompletedBackups(serverId: Uuid, keepCount: Int): List<BackupRow>
}
