package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

data class GroupRow(
    val id: Uuid,
    val name: String,
    val isSystem: Boolean,
    val permissions: List<String>,
    val createdAt: String,
)

interface GroupRepository {

    fun findById(id: Uuid): GroupRow?
    fun findByName(name: String): GroupRow?
    fun listAll(): List<GroupRow>
    fun create(name: String, isSystem: Boolean = false): GroupRow
    fun update(id: Uuid, name: String)
    fun delete(id: Uuid)
    fun getPermissions(groupId: Uuid): List<String>
    fun getPermissionsForGroups(groupIds: List<Uuid>): List<String>
    fun setPermissions(groupId: Uuid, permissions: List<String>)
    fun deletePermissionsForGroup(groupId: Uuid)
}
