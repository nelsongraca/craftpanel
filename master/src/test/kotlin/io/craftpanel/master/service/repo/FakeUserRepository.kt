package io.craftpanel.master.service.repo

import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

class FakeUserRepository : UserRepository {

    private val users = mutableMapOf<Uuid, MutableUser>()
    private val assignments = mutableMapOf<Uuid, MutableAssignment>()
    private val tokens = mutableMapOf<String, MutableToken>()

    data class MutableUser(
        val id: Uuid,
        var username: String,
        var email: String,
        var passwordHash: String,
        var isActive: Boolean = true,
        val createdAt: String = "2025-01-01T00:00:00Z",
    )

    data class MutableAssignment(
        val id: Uuid,
        val userId: Uuid,
        val groupId: Uuid,
        val scopeType: String,
        val scopeId: Uuid?,
    )

    data class MutableToken(
        val id: Uuid,
        val userId: Uuid,
        val tokenHash: String,
        val expiresAt: String,
        var revoked: Boolean = false,
    )

    override fun findById(id: Uuid): UserRow? = users[id]?.toRow()
    override fun findByEmail(email: String): UserRow? = users.values.firstOrNull { it.email == email }
        ?.toRow()

    override fun findByUsername(username: String): UserRow? = users.values.firstOrNull { it.username == username }
        ?.toRow()

    override fun listAll(): List<UserRow> = users.values.map { it.toRow() }
    override fun create(username: String, email: String, passwordHash: String): UserRow {
        val id = Uuid.random()
        val u = MutableUser(id, username, email, passwordHash)
        users[id] = u
        return u.toRow()
    }

    override fun update(id: Uuid, username: String?, email: String?, isActive: Boolean?) {
        val u = users[id] ?: return
        if (username != null) u.username = username
        if (email != null) u.email = email
        if (isActive != null) u.isActive = isActive
    }

    override fun delete(id: Uuid) {
        users.remove(id); assignments.values.removeAll { it.userId == id }; tokens.values.removeAll { it.userId == id }
    }

    override fun isActive(id: Uuid): Boolean = users[id]?.isActive ?: false

    override fun listAssignments(userId: Uuid): List<AssignmentRow> = assignments.values.filter { it.userId == userId }
        .map { it.toRow() }

    override fun createAssignment(userId: Uuid, groupId: Uuid, scopeType: String, scopeId: Uuid?): AssignmentRow {
        val id = Uuid.random()
        val a = MutableAssignment(id, userId, groupId, scopeType, scopeId)
        assignments[id] = a
        return a.toRow()
    }

    override fun deleteAssignment(id: Uuid) {
        assignments.remove(id)
    }

    override fun findAssignment(userId: Uuid, groupId: Uuid, scopeType: String, scopeId: Uuid?): AssignmentRow? =
        assignments.values.firstOrNull { it.userId == userId && it.groupId == groupId && it.scopeType == scopeType && it.scopeId == scopeId }
            ?.toRow()

    override fun deleteAssignmentsForUser(userId: Uuid) {
        assignments.values.removeAll { it.userId == userId }
    }

    override fun deleteAssignmentsForGroup(groupId: Uuid) {
        assignments.values.removeAll { it.groupId == groupId }
    }

    override fun issueRefreshToken(userId: Uuid, tokenHash: String, expiresAt: Instant) {
        val id = Uuid.random()
        tokens[tokenHash] = MutableToken(id, userId, tokenHash, expiresAt.toString())
    }

    override fun findRefreshTokenByHash(tokenHash: String): RefreshTokenRow? = tokens[tokenHash]?.let { RefreshTokenRow(it.id, it.userId, it.tokenHash, it.expiresAt, it.revoked) }
    override fun rotateRefreshToken(oldHash: String, newHash: String, expiresAt: Instant, userId: Uuid) {
        tokens[oldHash]?.revoked = true
        val id = Uuid.random()
        tokens[newHash] = MutableToken(id, userId, newHash, expiresAt.toString())
    }

    override fun revokeRefreshToken(tokenHash: String) {
        tokens[tokenHash]?.revoked = true
    }

    override fun revokeAllRefreshTokens(userId: Uuid) {
        tokens.values.filter { it.userId == userId }
            .forEach { it.revoked = true }
    }

    override fun deleteRefreshTokensForUser(userId: Uuid) {
        tokens.values.removeAll { it.userId == userId }
    }

    override fun getUserGlobalGroups(userId: Uuid): List<GroupAssignmentRow> = emptyList()

    private fun MutableUser.toRow() = UserRow(id, username, email, isActive, createdAt)
    private fun MutableAssignment.toRow() = AssignmentRow(id, userId, groupId, scopeType, scopeId)
}
