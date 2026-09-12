package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakeUserRepository : UserRepository {

    private val users = mutableMapOf<Uuid, MutableUser>()
    private val assignments = mutableMapOf<Uuid, MutableAssignment>()
    private val tokens = mutableMapOf<String, MutableToken>()
    private val trustedDevices = mutableMapOf<String, MutableTrustedDevice>()

    data class MutableUser(
        val id: Uuid,
        var username: String,
        var email: String,
        var passwordHash: String,
        var isActive: Boolean = true,
        val createdAt: String = "2025-01-01T00:00:00Z",
        var totpEnabled: Boolean = false,
        var totpSecret: String? = null,
        var mustChangePassword: Boolean = false
    )

    data class MutableAssignment(val id: Uuid, val userId: Uuid, val groupId: Uuid, val scopeType: String, val scopeId: Uuid?)

    data class MutableToken(val id: Uuid, val userId: Uuid, val tokenHash: String, val expiresAt: String, var revoked: Boolean = false, val trusted: Boolean = false, val deviceFingerprint: String? = null)

    data class MutableTrustedDevice(val id: Uuid, val userId: Uuid, val tokenHash: String, val deviceFingerprint: String, val userAgent: String, val expiresAt: String, var revoked: Boolean = false)

    override fun findById(id: Uuid): UserRow? = users[id]?.toRow()
    override fun findByEmail(email: String): UserRow? = users.values.firstOrNull { it.email == email }
        ?.toRow()

    override fun findByUsername(username: String): UserRow? = users.values.firstOrNull { it.username == username }
        ?.toRow()

    override fun findCredentials(email: String): CredentialRow? = users.values.firstOrNull { it.email == email }
        ?.let {
            CredentialRow(
                userId = it.id,
                username = it.username,
                email = it.email,
                passwordHash = it.passwordHash,
                isActive = it.isActive,
                totpEnabled = it.totpEnabled,
                mustChangePassword = it.mustChangePassword
            )
        }

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

    override fun findRefreshTokenByHash(tokenHash: String): RefreshTokenRow? = tokens[tokenHash]?.let { RefreshTokenRow(it.id, it.userId, it.tokenHash, it.expiresAt, it.revoked, it.trusted, it.deviceFingerprint) }

    override fun findTrustedRefreshTokenByFingerprint(userId: Uuid, fingerprintHash: String): RefreshTokenRow? = tokens.values.firstOrNull {
        it.userId == userId && it.trusted && !it.revoked && it.deviceFingerprint == fingerprintHash
    }?.let { RefreshTokenRow(it.id, it.userId, it.tokenHash, it.expiresAt, it.revoked, it.trusted, it.deviceFingerprint) }

    override fun findTrustedDevice(userId: Uuid, tokenHash: String, deviceFingerprint: String, userAgent: String): TrustedDeviceRow? =
        trustedDevices[tokenHash]?.takeIf {
            it.userId == userId && it.deviceFingerprint == deviceFingerprint && it.userAgent == userAgent && !it.revoked
        }?.let { TrustedDeviceRow(it.id, it.userId, it.tokenHash, it.deviceFingerprint, it.userAgent, it.expiresAt, it.revoked) }

    override fun getUserGlobalGroups(userId: Uuid): List<GroupAssignmentRow> = emptyList()

    override fun updatePassword(userId: Uuid, newHash: String) {
        users[userId]?.passwordHash = newHash
    }

    override fun setMustChangePassword(userId: Uuid, value: Boolean) {
        users[userId]?.mustChangePassword = value
    }

    override fun findTotpSecret(userId: Uuid): String? = users[userId]?.totpSecret

    override fun storeTotpSecret(userId: Uuid, encryptedSecret: String) {
        users[userId]?.totpSecret = encryptedSecret
    }

    override fun enableTotp(userId: Uuid) {
        users[userId]?.totpEnabled = true
    }

    override fun disableTotp(userId: Uuid) {
        users[userId]?.let {
            it.totpSecret = null
            it.totpEnabled = false
        }
    }

    private fun MutableUser.toRow() = UserRow(id, username, email, isActive, createdAt, totpEnabled, mustChangePassword)
    private fun MutableAssignment.toRow() = AssignmentRow(id, userId, groupId, scopeType, scopeId)
}
