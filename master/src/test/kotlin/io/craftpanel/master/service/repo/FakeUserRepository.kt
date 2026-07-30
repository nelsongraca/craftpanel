package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakeUserRepository : UserRepository {

    private val users = mutableMapOf<Uuid, MutableUser>()
    private val assignments = mutableMapOf<Uuid, MutableAssignment>()
    private val tokens = mutableMapOf<String, MutableToken>()

    data class MutableUser(val id: Uuid, var username: String, var email: String, var passwordHash: String, var isActive: Boolean = true, val createdAt: String = "2025-01-01T00:00:00Z")

    data class MutableAssignment(val id: Uuid, val userId: Uuid, val groupId: Uuid, val scopeType: String, val scopeId: Uuid?)

    data class MutableToken(val id: Uuid, val userId: Uuid, val tokenHash: String, val expiresAt: String, var revoked: Boolean = false)

    override fun findById(id: Uuid): UserRow? = users[id]?.toRow()
    override fun findByEmail(email: String): UserRow? = users.values.firstOrNull { it.email == email }
        ?.toRow()

    override fun findByUsername(username: String): UserRow? = users.values.firstOrNull { it.username == username }
        ?.toRow()

    override fun findCredentials(email: String): CredentialRow? = users.values.firstOrNull { it.email == email }
        ?.let { CredentialRow(it.id, it.username, it.email, it.passwordHash, it.isActive) }

    override fun listAll(): List<UserRow> = users.values.map { it.toRow() }

    override fun isActive(id: Uuid): Boolean = users[id]?.isActive ?: false

    override fun listAssignments(userId: Uuid): List<AssignmentRow> = assignments.values.filter { it.userId == userId }
        .map { it.toRow() }

    override fun findAssignment(userId: Uuid, groupId: Uuid, scopeType: String, scopeId: Uuid?): AssignmentRow? =
        assignments.values.firstOrNull { it.userId == userId && it.groupId == groupId && it.scopeType == scopeType && it.scopeId == scopeId }
            ?.toRow()

    fun addUser(username: String, email: String, passwordHash: String = "hash", isActive: Boolean = true, id: Uuid = Uuid.random()): UserRow {
        val u = MutableUser(id, username, email, passwordHash, isActive)
        users[id] = u
        return u.toRow()
    }

    fun addAssignment(userId: Uuid, groupId: Uuid, scopeType: String, scopeId: Uuid?, id: Uuid = Uuid.random()): AssignmentRow {
        val a = MutableAssignment(id, userId, groupId, scopeType, scopeId)
        assignments[id] = a
        return a.toRow()
    }

    override fun findRefreshTokenByHash(tokenHash: String): RefreshTokenRow? = tokens[tokenHash]?.let { RefreshTokenRow(it.id, it.userId, it.tokenHash, it.expiresAt, it.revoked) }

    override fun getUserGlobalGroups(userId: Uuid): List<GroupAssignmentRow> = emptyList()

    private fun MutableUser.toRow() = UserRow(id, username, email, isActive, createdAt)
    private fun MutableAssignment.toRow() = AssignmentRow(id, userId, groupId, scopeType, scopeId)
}