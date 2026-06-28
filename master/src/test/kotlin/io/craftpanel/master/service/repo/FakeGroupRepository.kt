package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakeGroupRepository : GroupRepository {

    private val groups = mutableMapOf<Uuid, MutableGroup>()

    data class MutableGroup(
        val id: Uuid,
        var name: String,
        val isSystem: Boolean,
        val permissions: MutableList<String> = mutableListOf(),
        val createdAt: String = "2025-01-01T00:00:00Z",
    )

    override fun findById(id: Uuid): GroupRow? = groups[id]?.toRow()
    override fun findByName(name: String): GroupRow? = groups.values.firstOrNull { it.name == name }
        ?.toRow()

    override fun listAll(): List<GroupRow> = groups.values.map { it.toRow() }

    override fun create(name: String, isSystem: Boolean): GroupRow {
        val id = Uuid.random()
        val g = MutableGroup(id, name, isSystem)
        groups[id] = g
        return g.toRow()
    }

    override fun update(id: Uuid, name: String) {
        groups[id]?.name = name
    }

    override fun delete(id: Uuid) {
        groups.remove(id)
    }

    override fun getPermissions(groupId: Uuid): List<String> = groups[groupId]?.permissions?.toList() ?: emptyList()
    override fun getPermissionsForGroups(groupIds: List<Uuid>): List<String> = groupIds.flatMap { getPermissions(it) }
    override fun setPermissions(groupId: Uuid, permissions: List<String>) {
        groups[groupId]?.permissions?.apply { clear(); addAll(permissions) }
    }

    override fun deletePermissionsForGroup(groupId: Uuid) {
        groups[groupId]?.permissions?.clear()
    }

    private fun MutableGroup.toRow() = GroupRow(id, name, isSystem, permissions.toList(), createdAt)
}
