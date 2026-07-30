package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

data class ModRow(
    val id: Uuid,
    val serverId: Uuid,
    val modrinthProjectId: String,
    val displayName: String,
    val pinStrategy: String,
    val pinnedVersionId: String?,
    val installedVersionId: String?,
    val createdAt: String,
    val updatedAt: String
)

interface ModRepository {

    fun listMods(serverId: Uuid): List<ModRow>
    fun findModById(id: Uuid): ModRow?
    fun findModByProjectId(serverId: Uuid, projectId: String): ModRow?
}