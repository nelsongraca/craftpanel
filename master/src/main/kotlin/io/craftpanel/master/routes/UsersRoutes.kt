package io.craftpanel.master.routes

import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.database.schema.RefreshTokens
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.util.toKotlinUuid
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

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

private fun forbidden() = HttpStatusCode.Forbidden to ErrorResponse("Insufficient permissions")

fun Route.usersRoutes() {
    authenticate("auth-jwt") {
        route("/api/users") {

            get("", {
                operationId = "listUsers"
                summary = "List users"
                response {
                    code(HttpStatusCode.OK) { body<UsersListResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.users")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@get
                }
                val users = transaction {
                    Users.selectAll().map { it.toUserResponse() }
                }
                call.respond(UsersListResponse(users))
            }

            post("", {
                operationId = "createUser"
                summary = "Create user"
                request { body<CreateUserRequest>() }
                response {
                    code(HttpStatusCode.Created) { body<UserResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.users")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@post
                }
                val req = call.receive<CreateUserRequest>()
                val hash = Argon2Hasher.hash(req.password)

                val existing = transaction {
                    Users.selectAll()
                        .where { (Users.username eq req.username) or (Users.email eq req.email) }
                        .firstOrNull()
                }
                if (existing != null) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Username or email already taken"))
                    return@post
                }

                val createdId = transaction {
                    Users.insert {
                        it[Users.username] = req.username
                        it[Users.email] = req.email
                        it[Users.passwordHash] = hash
                    }[Users.id]
                }

                val user = transaction {
                    Users.selectAll().where { Users.id eq createdId }.first().toUserResponse()
                }
                call.respond(HttpStatusCode.Created, user)
            }

            get("/{id}", {
                operationId = "getUser"
                summary = "Get user"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { body<UserResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.users")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@get
                }
                val targetId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: run { call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found")); return@get }

                val user = transaction {
                    Users.selectAll().where { Users.id eq targetId.toKotlinUuid() }.firstOrNull()?.toUserResponse()
                }
                if (user == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                    return@get
                }
                call.respond(user)
            }

            patch("/{id}", {
                operationId = "updateUser"
                summary = "Update user"
                request { pathParameter<String>("id"); body<PatchUserRequest>() }
                response {
                    code(HttpStatusCode.OK) { body<UserResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.users")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@patch
                }
                val targetId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: run { call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found")); return@patch }

                val existing = transaction {
                    Users.selectAll().where { Users.id eq targetId.toKotlinUuid() }.firstOrNull()
                }
                if (existing == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                    return@patch
                }

                val req = call.receive<PatchUserRequest>()

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
                    if (conflict != null) {
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Username or email already taken"))
                        return@patch
                    }
                }

                transaction {
                    Users.update({ Users.id eq targetId.toKotlinUuid() }) {
                        if (req.username != null) it[Users.username] = req.username
                        if (req.email != null) it[Users.email] = req.email
                        if (req.isActive != null) it[Users.isActive] = req.isActive
                    }
                }

                val updated = transaction {
                    Users.selectAll().where { Users.id eq targetId.toKotlinUuid() }.first().toUserResponse()
                }
                call.respond(updated)
            }

            delete("/{id}", {
                operationId = "deleteUser"
                summary = "Delete user"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.users")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@delete
                }
                val targetId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: run { call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found")); return@delete }

                val deleted = transaction {
                    val exists = Users.selectAll().where { Users.id eq targetId.toKotlinUuid() }.firstOrNull() != null
                    if (!exists) return@transaction false
                    UserGroupAssignments.deleteWhere { UserGroupAssignments.userId eq targetId.toKotlinUuid() }
                    RefreshTokens.deleteWhere { RefreshTokens.userId eq targetId.toKotlinUuid() }
                    Users.deleteWhere { Users.id eq targetId.toKotlinUuid() }
                    true
                }
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                    return@delete
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun org.jetbrains.exposed.v1.core.ResultRow.toUserResponse() = UserResponse(
    id = this[Users.id].toString(),
    username = this[Users.username],
    email = this[Users.email],
    isActive = this[Users.isActive],
    createdAt = this[Users.createdAt].toString(),
)
