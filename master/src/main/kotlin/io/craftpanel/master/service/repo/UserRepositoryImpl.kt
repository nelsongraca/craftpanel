package io.craftpanel.master.service.repo

import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.RefreshTokens
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.util.toUtcString
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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
                    userId = it[Users.id],
                    username = it[Users.username],
                    email = it[Users.email],
                    passwordHash = it[Users.passwordHash],
                    isActive = it[Users.isActive]
                )
            }
    }

    override fun listAll(): List<UserRow> = transaction {
        Users.selectAll()
            .map { it.toUserRow() }
    }

    override fun create(username: String, email: String, passwordHash: String): UserRow = transaction {
        val id = Users.insert {
            it[Users.username] = username
            it[Users.email] = email
            it[Users.passwordHash] = passwordHash
        }[Users.id]
        Users.selectAll()
            .where { Users.id eq id }
            .first()
            .toUserRow()
    }

    override fun update(id: Uuid, username: String?, email: String?, isActive: Boolean?) {
        transaction {
            Users.update({ Users.id eq id }) {
                if (username != null) it[Users.username] = username
                if (email != null) it[Users.email] = email
                if (isActive != null) it[Users.isActive] = isActive
            }
        }
    }

    override fun delete(id: Uuid) {
        transaction {
            UserGroupAssignments.deleteWhere { UserGroupAssignments.userId eq id }
            RefreshTokens.deleteWhere { RefreshTokens.userId eq id }
            Users.deleteWhere { Users.id eq id }
        }
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

    override fun createAssignment(userId: Uuid, groupId: Uuid, scopeType: String, scopeId: Uuid?): AssignmentRow = transaction {
        val id = UserGroupAssignments.insert {
            it[UserGroupAssignments.userId] = userId
            it[UserGroupAssignments.groupId] = groupId
            it[UserGroupAssignments.scopeType] = scopeType
            it[UserGroupAssignments.scopeId] = scopeId
        }[UserGroupAssignments.id]
        UserGroupAssignments.selectAll()
            .where { UserGroupAssignments.id eq id }
            .first()
            .toAssignmentRow()
    }

    override fun deleteAssignment(id: Uuid) {
        transaction {
            UserGroupAssignments.deleteWhere { UserGroupAssignments.id eq id }
        }
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

    override fun deleteAssignmentsForUser(userId: Uuid) {
        transaction { UserGroupAssignments.deleteWhere { UserGroupAssignments.userId eq userId } }
    }

    override fun deleteAssignmentsForGroup(groupId: Uuid) {
        transaction { UserGroupAssignments.deleteWhere { UserGroupAssignments.groupId eq groupId } }
    }

    override fun issueRefreshToken(userId: Uuid, tokenHash: String, expiresAt: Instant) {
        transaction {
            RefreshTokens.insert {
                it[RefreshTokens.userId] = userId
                it[RefreshTokens.tokenHash] = tokenHash
                it[RefreshTokens.expiresAt] = expiresAt.toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun findRefreshTokenByHash(tokenHash: String): RefreshTokenRow? = transaction {
        RefreshTokens.selectAll()
            .where { RefreshTokens.tokenHash eq tokenHash }
            .firstOrNull()
            ?.let {
                RefreshTokenRow(
                    id = it[RefreshTokens.id],
                    userId = it[RefreshTokens.userId],
                    tokenHash = it[RefreshTokens.tokenHash],
                    expiresAt = it[RefreshTokens.expiresAt].toString(),
                    revoked = it[RefreshTokens.revoked]
                )
            }
    }

    override fun rotateRefreshToken(oldHash: String, newHash: String, expiresAt: Instant, userId: Uuid) {
        transaction {
            RefreshTokens.update({ RefreshTokens.tokenHash eq oldHash }) {
                it[RefreshTokens.revoked] = true
            }
            RefreshTokens.insert {
                it[RefreshTokens.userId] = userId
                it[RefreshTokens.tokenHash] = newHash
                it[RefreshTokens.expiresAt] = expiresAt.toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun revokeRefreshToken(tokenHash: String) {
        transaction {
            RefreshTokens.update({ RefreshTokens.tokenHash eq tokenHash }) {
                it[RefreshTokens.revoked] = true
            }
        }
    }

    override fun revokeAllRefreshTokens(userId: Uuid) {
        transaction {
            RefreshTokens.update({ RefreshTokens.userId eq userId }) {
                it[RefreshTokens.revoked] = true
            }
        }
    }

    override fun deleteRefreshTokensForUser(userId: Uuid) {
        transaction { RefreshTokens.deleteWhere { RefreshTokens.userId eq userId } }
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
                    groupId = it[Groups.id],
                    groupName = it[Groups.name]
                )
            }
    }
}

private fun org.jetbrains.exposed.v1.core.ResultRow.toUserRow() = UserRow(
    id = this[Users.id],
    username = this[Users.username],
    email = this[Users.email],
    isActive = this[Users.isActive],
    createdAt = this[Users.createdAt].toUtcString()
)

private fun org.jetbrains.exposed.v1.core.ResultRow.toAssignmentRow() = AssignmentRow(
    id = this[UserGroupAssignments.id],
    userId = this[UserGroupAssignments.userId],
    groupId = this[UserGroupAssignments.groupId],
    scopeType = this[UserGroupAssignments.scopeType],
    scopeId = this[UserGroupAssignments.scopeId]
)
