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

    override fun createMod(serverId: Uuid, modrinthProjectId: String, displayName: String, pinStrategy: String, pinnedVersionId: String?, installedVersionId: String?): ModRow {
        val id = Uuid.random()
        val m = FakeServerRepository.MutableMod(id, serverId, modrinthProjectId, displayName, pinStrategy, pinnedVersionId, installedVersionId)
        state.mods.getOrPut(serverId) { mutableMapOf() }[id] = m
        return m.toRow()
    }

    override fun updateMod(id: Uuid, pinStrategy: String?, pinnedVersionId: String?, installedVersionId: String?) {
        val m = state.mods.values.flatMap { it.values }
            .firstOrNull { it.id == id } ?: return
        if (pinStrategy != null) m.pinStrategy = pinStrategy
        if (pinnedVersionId != null) m.pinnedVersionId = pinnedVersionId
        if (installedVersionId != null) m.installedVersionId = installedVersionId
    }

    override fun deleteMod(id: Uuid) {
        state.mods.values.forEach { it.remove(id) }
    }

    override fun deleteModsForServer(serverId: Uuid) {
        state.mods.remove(serverId)
    }

    private fun FakeServerRepository.MutableMod.toRow() = ModRow(id, serverId, modrinthProjectId, displayName, pinStrategy, pinnedVersionId, installedVersionId, createdAt, updatedAt)
}
