package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.schema.*
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.util.toUtcString
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.uuid.Uuid

class UserRepositoryImpl : UserRepository {

    override fun findById(id: Uuid): UserRow? = transaction {
        Users.selectAll()
            .where { Users.id eq id }
            .firstOrNull()
            ?.toUserRow()
    }

    override fun findByEmail(email: String): UserRow? = transaction {
        Users.selectAll()
            .where { Users.email eq email }
            .firstOrNull()
            ?.toUserRow()
    }

    override fun findByUsername(username: String): UserRow? = transaction {
        Users.selectAll()
            .where { Users.username eq username }
            .firstOrNull()
            ?.toUserRow()
    }

    override fun findCredentials(email: String): CredentialRow? = transaction {
        Users.selectAll()
            .where { Users.email eq email }
            .firstOrNull()
            ?.let {
                CredentialRow(
                    userId = it[Users.id].value,
                    username = it[Users.username],
                    email = it[Users.email],
                    passwordHash = it[Users.passwordHash],
                    isActive = it[Users.isActive],
                    totpEnabled = it[Users.totpEnabled],
                    mustChangePassword = it[Users.mustChangePassword]
                )
            }
    }

    override fun listAll(): List<UserRow> = transaction {
        Users.selectAll()
            .map { it.toUserRow() }
    }

    override fun isActive(id: Uuid): Boolean = transaction {
        Users.selectAll()
            .where { Users.id eq id }
            .firstOrNull()
            ?.let { it[Users.isActive] }
            ?: false
    }

    override fun listAssignments(userId: Uuid): List<AssignmentRow> = transaction {
        UserGroupAssignments.selectAll()
            .where { UserGroupAssignments.userId eq userId }
            .map { it.toAssignmentRow() }
    }

    override fun findAssignment(userId: Uuid, groupId: Uuid, scopeType: String, scopeId: Uuid?): AssignmentRow? = transaction {
        UserGroupAssignments.selectAll()
            .where {
                (UserGroupAssignments.userId eq userId) and
                    (UserGroupAssignments.groupId eq groupId) and
                    (UserGroupAssignments.scopeType eq scopeType) and
                    (if (scopeId != null) (UserGroupAssignments.scopeId eq scopeId) else (UserGroupAssignments.scopeId.isNull()))
            }
            .firstOrNull()
            ?.toAssignmentRow()
    }

    override fun findRefreshTokenByHash(tokenHash: String): RefreshTokenRow? = transaction {
        RefreshTokens.selectAll()
            .where { RefreshTokens.tokenHash eq tokenHash }
            .firstOrNull()
            ?.let {
                RefreshTokenRow(
                    id = it[RefreshTokens.id],
                    userId = it[RefreshTokens.userId].value,
                    tokenHash = it[RefreshTokens.tokenHash],
                    expiresAt = it[RefreshTokens.expiresAt].toUtcString(),
                    revoked = it[RefreshTokens.revoked],
                    trusted = it[RefreshTokens.trusted],
                    deviceFingerprint = it[RefreshTokens.deviceFingerprint]
                )
            }
    }

    override fun findTrustedRefreshTokenByFingerprint(userId: Uuid, fingerprintHash: String): RefreshTokenRow? = transaction {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        RefreshTokens.selectAll()
            .where {
                (RefreshTokens.userId eq userId) and
                    (RefreshTokens.trusted eq true) and
                    (RefreshTokens.revoked eq false) and
                    (RefreshTokens.deviceFingerprint eq fingerprintHash) and
                    (RefreshTokens.expiresAt greaterEq now)
            }
            .firstOrNull()
            ?.let {
                RefreshTokenRow(
                    id = it[RefreshTokens.id],
                    userId = it[RefreshTokens.userId].value,
                    tokenHash = it[RefreshTokens.tokenHash],
                    expiresAt = it[RefreshTokens.expiresAt].toUtcString(),
                    revoked = it[RefreshTokens.revoked],
                    trusted = it[RefreshTokens.trusted],
                    deviceFingerprint = it[RefreshTokens.deviceFingerprint]
                )
            }
    }

    override fun getUserGlobalGroups(userId: Uuid): List<GroupAssignmentRow> = transaction {
        (UserGroupAssignments innerJoin Groups)
            .selectAll()
            .where {
                (UserGroupAssignments.userId eq userId) and
                    (UserGroupAssignments.scopeType eq "GLOBAL")
            }
            .map {
                GroupAssignmentRow(
                    groupId = it[Groups.id].value,
                    groupName = it[Groups.name]
                )
            }
    }

    override fun updatePassword(userId: Uuid, newHash: String) {
        transaction {
            Users.update({ Users.id eq userId }) {
                it[passwordHash] = newHash
            }
        }
    }

    override fun setMustChangePassword(userId: Uuid, value: Boolean) {
        transaction {
            Users.update({ Users.id eq userId }) {
                it[mustChangePassword] = value
            }
        }
    }

    override fun findTotpSecret(userId: Uuid): String? = transaction {
        Users.selectAll()
            .where { Users.id eq userId }
            .firstOrNull()
            ?.get(Users.totpSecret)
    }

    override fun storeTotpSecret(userId: Uuid, encryptedSecret: String) {
        transaction {
            Users.update({ Users.id eq userId }) {
                it[totpSecret] = encryptedSecret
            }
        }
    }

    override fun enableTotp(userId: Uuid) {
        transaction {
            Users.update({ Users.id eq userId }) {
                it[totpEnabled] = true
            }
        }
    }

    override fun disableTotp(userId: Uuid) {
        transaction {
            Users.update({ Users.id eq userId }) {
                it[totpSecret] = null
                it[totpEnabled] = false
            }
        }
    }
}

private fun ResultRow.toUserRow() = UserRow(
    id = this[Users.id].value,
    username = this[Users.username],
    email = this[Users.email],
    isActive = this[Users.isActive],
    createdAt = this[Users.createdAt].toUtcString(),
    totpEnabled = this[Users.totpEnabled],
    mustChangePassword = this[Users.mustChangePassword]
)

private fun ResultRow.toAssignmentRow() = AssignmentRow(
    id = this[UserGroupAssignments.id],
    userId = this[UserGroupAssignments.userId].value,
    groupId = this[UserGroupAssignments.groupId].value,
    scopeType = this[UserGroupAssignments.scopeType],
    scopeId = this[UserGroupAssignments.scopeId]
)