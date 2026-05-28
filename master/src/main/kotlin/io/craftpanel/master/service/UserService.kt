package io.craftpanel.master.service

import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.database.schema.RefreshTokens
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.util.toKotlinUuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.*

@Serializable
data class CreateUserRequest(val username: String, val email: String, val password: String)

@Serializable
data class PatchUserRequest(
    val username: String? = null,
    val email: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
)

@Serializable
data class UserResponse(
    val id: String,
    val username: String,
    val email: String,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class UsersListResponse(val users: List<UserResponse>)

class UserService {

    fun listUsers(): UsersListResponse =
        UsersListResponse(transaction {
            Users.selectAll()
                .map { it.toUserResponse() }
        })

    fun createUser(req: CreateUserRequest): UserResponse {
        val hash = Argon2Hasher.hash(req.password)
        val existing = transaction {
            Users.selectAll()
                .where { (Users.username eq req.username) or (Users.email eq req.email) }
                .firstOrNull()
        }
        if (existing != null) throw UnprocessableException("Username or email already taken")
        val createdId = transaction {
            Users.insert {
                it[Users.username] = req.username
                it[Users.email] = req.email
                it[Users.passwordHash] = hash
            }[Users.id]
        }
        return transaction {
            Users.selectAll()
                .where { Users.id eq createdId }
                .first()
                .toUserResponse()
        }
    }

    fun getUser(targetId: UUID): UserResponse =
        transaction {
            Users.selectAll()
                .where { Users.id eq targetId.toKotlinUuid() }
                .firstOrNull()
                ?.toUserResponse()
        } ?: throw NotFoundException("User not found")

    fun updateUser(targetId: UUID, req: PatchUserRequest): UserResponse {
        transaction {
            Users.selectAll()
                .where { Users.id eq targetId.toKotlinUuid() }
                .firstOrNull()
        }
            ?: throw NotFoundException("User not found")

        if (req.username != null || req.email != null) {
            val conflict = transaction {
                val conditions = buildList {
                    if (req.username != null) add(Users.username eq req.username)
                    if (req.email != null) add(Users.email eq req.email)
                }
                Users.selectAll()
                    .where { conditions.reduce { a, b -> a or b } and (Users.id neq targetId.toKotlinUuid()) }
                    .firstOrNull()
            }
            if (conflict != null) throw UnprocessableException("Username or email already taken")
        }

        transaction {
            Users.update({ Users.id eq targetId.toKotlinUuid() }) {
                if (req.username != null) it[Users.username] = req.username
                if (req.email != null) it[Users.email] = req.email
                if (req.isActive != null) it[Users.isActive] = req.isActive
            }
        }
        return transaction {
            Users.selectAll()
                .where { Users.id eq targetId.toKotlinUuid() }
                .first()
                .toUserResponse()
        }
    }

    fun deleteUser(targetId: UUID) {
        val deleted = transaction {
            val exists = Users.selectAll()
                .where { Users.id eq targetId.toKotlinUuid() }
                .firstOrNull() != null
            if (!exists) return@transaction false
            UserGroupAssignments.deleteWhere { UserGroupAssignments.userId eq targetId.toKotlinUuid() }
            RefreshTokens.deleteWhere { RefreshTokens.userId eq targetId.toKotlinUuid() }
            Users.deleteWhere { Users.id eq targetId.toKotlinUuid() }
            true
        }
        if (!deleted) throw NotFoundException("User not found")
    }
}

internal fun org.jetbrains.exposed.v1.core.ResultRow.toUserResponse() = UserResponse(
    id = this[Users.id].toString(),
    username = this[Users.username],
    email = this[Users.email],
    isActive = this[Users.isActive],
    createdAt = this[Users.createdAt].toString(),
)
