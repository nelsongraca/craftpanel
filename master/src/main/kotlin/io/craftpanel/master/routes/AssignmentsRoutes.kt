package io.craftpanel.master.routes

import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.util.toKotlinUuid
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
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
import java.util.UUID

@Serializable
data class AssignmentResponse(
    val id: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("scope_type") val scopeType: String,
    @SerialName("scope_id") val scopeId: String?,
)

@Serializable
data class AssignmentsListResponse(val assignments: List<AssignmentResponse>)

@Serializable
data class CreateAssignmentRequest(
    @SerialName("group_id") val groupId: String,
    @SerialName("scope_type") val scopeType: String,
    @SerialName("scope_id") val scopeId: String? = null,
)

fun Route.assignmentsRoutes() {
    authenticate("auth-jwt") {
        route("/api/users/{userId}/assignments") {

            get("", {
                operationId = "listUserAssignments"
                summary = "List group assignments for a user"
                request { pathParameter<String>("userId") }
                response {
                    code(HttpStatusCode.OK) { body<AssignmentsListResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val callerId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(callerId, "system.users")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@get
                }
                val targetId = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: run { call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found")); return@get }

                val exists = transaction {
                    Users.selectAll().where { Users.id eq targetId.toKotlinUuid() }.firstOrNull() != null
                }
                if (!exists) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                    return@get
                }

                val assignments = transaction {
                    UserGroupAssignments.selectAll()
                        .where { UserGroupAssignments.userId eq targetId.toKotlinUuid() }
                        .map { it.toAssignmentResponse() }
                }
                call.respond(AssignmentsListResponse(assignments))
            }

            post("", {
                operationId = "createAssignment"
                summary = "Add a group assignment to a user"
                request { pathParameter<String>("userId"); body<CreateAssignmentRequest>() }
                response {
                    code(HttpStatusCode.Created) { body<AssignmentResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val callerId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(callerId, "system.users")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@post
                }
                val targetId = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: run { call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found")); return@post }

                val req = call.receive<CreateAssignmentRequest>()

                if (req.scopeType !in setOf("GLOBAL", "SERVER", "NETWORK")) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("scope_type must be GLOBAL, SERVER, or NETWORK"))
                    return@post
                }
                if (req.scopeType != "GLOBAL" && req.scopeId == null) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("scope_id required for ${req.scopeType} scope"))
                    return@post
                }

                val groupId = runCatching { UUID.fromString(req.groupId) }.getOrNull()
                    ?: run { call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Invalid group_id")); return@post }
                val scopeId = req.scopeId?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                        ?: run { call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Invalid scope_id")); return@post }
                }

                val validation = transaction {
                    val userExists = Users.selectAll().where { Users.id eq targetId.toKotlinUuid() }.firstOrNull() != null
                    val groupExists = Groups.selectAll().where { Groups.id eq groupId.toKotlinUuid() }.firstOrNull() != null
                    val scopeExists = when {
                        scopeId == null -> true
                        req.scopeType == "SERVER" -> Servers.selectAll().where { Servers.id eq scopeId.toKotlinUuid() }.firstOrNull() != null
                        req.scopeType == "NETWORK" -> ServerNetworks.selectAll().where { ServerNetworks.id eq scopeId.toKotlinUuid() }.firstOrNull() != null
                        else -> true
                    }
                    val alreadyExists = UserGroupAssignments.selectAll().where {
                        (UserGroupAssignments.userId eq targetId.toKotlinUuid()) and
                        (UserGroupAssignments.groupId eq groupId.toKotlinUuid()) and
                        (UserGroupAssignments.scopeType eq req.scopeType) and
                        if (scopeId != null) (UserGroupAssignments.scopeId eq scopeId.toKotlinUuid()) else (UserGroupAssignments.scopeId.isNull())
                    }.firstOrNull() != null
                    Triple(userExists && groupExists && scopeExists, alreadyExists, userExists && groupExists)
                }

                if (!validation.third) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("User or group not found"))
                    return@post
                }
                if (!validation.first) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Scope target not found"))
                    return@post
                }
                if (validation.second) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Assignment already exists"))
                    return@post
                }

                val createdId = transaction {
                    UserGroupAssignments.insert {
                        it[UserGroupAssignments.userId] = targetId.toKotlinUuid()
                        it[UserGroupAssignments.groupId] = groupId.toKotlinUuid()
                        it[UserGroupAssignments.scopeType] = req.scopeType
                        it[UserGroupAssignments.scopeId] = scopeId?.toKotlinUuid()
                    }[UserGroupAssignments.id]
                }

                val assignment = transaction {
                    UserGroupAssignments.selectAll()
                        .where { UserGroupAssignments.id eq createdId }
                        .first().toAssignmentResponse()
                }
                call.respond(HttpStatusCode.Created, assignment)
            }

            delete("/{assignmentId}", {
                operationId = "deleteAssignment"
                summary = "Remove a group assignment from a user"
                request { pathParameter<String>("userId"); pathParameter<String>("assignmentId") }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val callerId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(callerId, "system.users")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@delete
                }
                val targetId = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: run { call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found")); return@delete }
                val assignmentId = call.parameters["assignmentId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: run { call.respond(HttpStatusCode.NotFound, ErrorResponse("Assignment not found")); return@delete }

                val deleted = transaction {
                    val exists = UserGroupAssignments.selectAll().where {
                        (UserGroupAssignments.id eq assignmentId.toKotlinUuid()) and
                        (UserGroupAssignments.userId eq targetId.toKotlinUuid())
                    }.firstOrNull() != null
                    if (!exists) return@transaction false
                    UserGroupAssignments.deleteWhere {
                        (UserGroupAssignments.id eq assignmentId.toKotlinUuid()) and
                        (UserGroupAssignments.userId eq targetId.toKotlinUuid())
                    }
                    true
                }
                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Assignment not found"))
                    return@delete
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun org.jetbrains.exposed.v1.core.ResultRow.toAssignmentResponse() = AssignmentResponse(
    id = this[UserGroupAssignments.id].toString(),
    groupId = this[UserGroupAssignments.groupId].toString(),
    scopeType = this[UserGroupAssignments.scopeType],
    scopeId = this[UserGroupAssignments.scopeId]?.toString(),
)
