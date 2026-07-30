package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakeModRepository(private val state: FakeRepositories) : ModRepository {

    override fun listMods(serverId: Uuid): List<ModRow> = state.mods[serverId]?.values?.map { it.toRow() }
        ?.toList() ?: emptyList()

    override fun findModById(id: Uuid): ModRow? = state.mods.values.flatMap { it.values }
        .firstOrNull { it.id == id }
        ?.toRow()

    override fun findModByProjectId(serverId: Uuid, projectId: String): ModRow? = state.mods[serverId]?.values?.firstOrNull { it.modrinthProjectId == projectId }
        ?.toRow()

    private fun FakeServerRepository.MutableMod.toRow() = ModRow(id, serverId, modrinthProjectId, displayName, pinStrategy, pinnedVersionId, installedVersionId, createdAt, updatedAt)
}