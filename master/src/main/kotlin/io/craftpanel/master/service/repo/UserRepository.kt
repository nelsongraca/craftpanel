package io.craftpanel.master.service.repo

import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

data class UserRow(val id: Uuid, val username: String, val email: String, val isActive: Boolean, val createdAt: String)

data class AssignmentRow(val id: Uuid, val userId: Uuid, val groupId: Uuid, val scopeType: String, val scopeId: Uuid?)

data class GroupAssignmentRow(val groupId: Uuid, val groupName: String)

data class CredentialRow(val userId: Uuid, val username: String, val email: String, val passwordHash: String, val isActive: Boolean)

interface UserRepository {

    fun findById(id: Uuid): UserRow?
    fun findByEmail(email: String): UserRow?
    fun findByUsername(username: String): UserRow?
    fun findCredentials(email: String): CredentialRow?
    fun listAll(): List<UserRow>
    fun create(username: String, email: String, passwordHash: String): UserRow
    fun update(id: Uuid, username: String?, email: String?, isActive: Boolean?)
    fun delete(id: Uuid)
    fun isActive(id: Uuid): Boolean

    fun listAssignments(userId: Uuid): List<AssignmentRow>
    fun createAssignment(userId: Uuid, groupId: Uuid, scopeType: String, scopeId: Uuid?): AssignmentRow
    fun deleteAssignment(id: Uuid)
    fun findAssignment(userId: Uuid, groupId: Uuid, scopeType: String, scopeId: Uuid?): AssignmentRow?
    fun deleteAssignmentsForUser(userId: Uuid)
    fun deleteAssignmentsForGroup(groupId: Uuid)

    fun issueRefreshToken(userId: Uuid, tokenHash: String, expiresAt: Instant)
    fun findRefreshTokenByHash(tokenHash: String): RefreshTokenRow?
    fun rotateRefreshToken(oldHash: String, newHash: String, expiresAt: Instant, userId: Uuid)
    fun revokeRefreshToken(tokenHash: String)
    fun revokeAllRefreshTokens(userId: Uuid)
    fun deleteRefreshTokensForUser(userId: Uuid)

    fun getUserGlobalGroups(userId: Uuid): List<GroupAssignmentRow>
}

data class RefreshTokenRow(val id: Uuid, val userId: Uuid, val tokenHash: String, val expiresAt: String, val revoked: Boolean)
