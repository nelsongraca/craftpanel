package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

data class UserRow(val id: Uuid, val username: String, val email: String, val isActive: Boolean, val createdAt: String, val totpEnabled: Boolean = false, val mustChangePassword: Boolean = false)

data class AssignmentRow(val id: Uuid, val userId: Uuid, val groupId: Uuid, val scopeType: String, val scopeId: Uuid?)

data class GroupAssignmentRow(val groupId: Uuid, val groupName: String)

data class CredentialRow(
    val userId: Uuid,
    val username: String,
    val email: String,
    val passwordHash: String,
    val isActive: Boolean,
    val totpEnabled: Boolean = false,
    val mustChangePassword: Boolean = false
)

data class RefreshTokenRow(val id: Uuid, val userId: Uuid, val tokenHash: String, val expiresAt: String, val revoked: Boolean, val trusted: Boolean = false, val deviceFingerprint: String? = null)

data class TrustedDeviceRow(val id: Uuid, val userId: Uuid, val tokenHash: String, val deviceFingerprint: String, val userAgent: String, val expiresAt: String, val revoked: Boolean = false)

interface UserRepository {

    fun findById(id: Uuid): UserRow?
    fun findByEmail(email: String): UserRow?
    fun findByUsername(username: String): UserRow?
    fun findCredentials(email: String): CredentialRow?
    fun listAll(): List<UserRow>
    fun isActive(id: Uuid): Boolean

    fun listAssignments(userId: Uuid): List<AssignmentRow>
    fun findAssignment(userId: Uuid, groupId: Uuid, scopeType: String, scopeId: Uuid?): AssignmentRow?

    fun findRefreshTokenByHash(tokenHash: String): RefreshTokenRow?

    fun findTrustedDevice(userId: Uuid, tokenHash: String, deviceFingerprint: String, userAgent: String): TrustedDeviceRow?

    fun getUserGlobalGroups(userId: Uuid): List<GroupAssignmentRow>

    fun updatePassword(userId: Uuid, newHash: String)

    fun setMustChangePassword(userId: Uuid, value: Boolean)

    fun findTotpSecret(userId: Uuid): String?
    fun storeTotpSecret(userId: Uuid, encryptedSecret: String)
    fun enableTotp(userId: Uuid)
    fun disableTotp(userId: Uuid)
    fun findTrustedRefreshTokenByFingerprint(userId: Uuid, fingerprintHash: String): RefreshTokenRow?
}