package io.craftpanel.master.routes

import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.database.schema.GroupPermissions
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.util.toKotlinUuid
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
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
data class GroupResponse(
    val id: String,
    val name: String,
    @SerialName("is_system") val isSystem: Boolean,
    val permissions: List<String>,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class CreateGroupRequest(val name: String)

@Serializable
data class PatchGroupRequest(val name: String)

@Serializable
data class PutGroupPermissionsRequest(val permissions: List<String>)

private val VALID_PERMISSIONS = setOf(
    "system.settings", "system.users", "system.nodes",
    "server.create", "server.delete", "server.start", "server.stop",
    "server.restart", "server.configure", "server.resources", "server.files",
    "server.mods", "server.console", "server.export", "server.backup",
    "server.upgrade", "server.migrate", "server.view",
)

fun Route.groupsRoutes() {
    authenticate("auth-jwt") {
        route("/api/groups") {

            get("", {
                operationId = "listGroups"
                summary = "List groups"
                response {
                    code(HttpStatusCode.OK) { body<List<GroupResponse>>() }
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
                val groups = transaction { fetchAllGroups() }
                call.respond(groups)
            }

            post("", {
                operationId = "createGroup"
                summary = "Create group"
                request { body<CreateGroupRequest>() }
                response {
                    code(HttpStatusCode.Created) { body<GroupResponse>() }
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
                val req = call.receive<CreateGroupRequest>()
                val exists = transaction {
                    Groups.selectAll().where { Groups.name eq req.name }.firstOrNull() != null
                }
                if (exists) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Group name already taken"))
                    return@post
                }
                val createdId = transaction {
                    Groups.insert { it[Groups.name] = req.name }[Groups.id]
                }
                val group = transaction { fetchGroup(createdId.let { UUID.fromString(it.toString()) }) }!!
                call.respond(HttpStatusCode.Created, group)
            }

            get("/{id}", {
                operationId = "getGroup"
                summary = "Get group"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { body<GroupResponse>() }
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
                    ?: run { call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found")); return@get }

                val group = transaction { fetchGroup(targetId) }
                if (group == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                    return@get
                }
                call.respond(group)
            }

            patch("/{id}", {
                operationId = "updateGroup"
                summary = "Update group name"
                request { pathParameter<String>("id"); body<PatchGroupRequest>() }
                response {
                    code(HttpStatusCode.OK) { body<GroupResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
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
                    ?: run { call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found")); return@patch }

                val existing = transaction {
                    Groups.selectAll().where { Groups.id eq targetId.toKotlinUuid() }.firstOrNull()
                }
                if (existing == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                    return@patch
                }
                if (existing[Groups.isSystem]) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot modify a system group"))
                    return@patch
                }
                val req = call.receive<PatchGroupRequest>()
                transaction {
                    Groups.update({ Groups.id eq targetId.toKotlinUuid() }) { it[Groups.name] = req.name }
                }
                val updated = transaction { fetchGroup(targetId) }!!
                call.respond(updated)
            }

            delete("/{id}", {
                operationId = "deleteGroup"
                summary = "Delete group"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
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
                    ?: run { call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found")); return@delete }

                val existing = transaction {
                    Groups.selectAll().where { Groups.id eq targetId.toKotlinUuid() }.firstOrNull()
                }
                if (existing == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                    return@delete
                }
                if (existing[Groups.isSystem]) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot delete a system group"))
                    return@delete
                }
                transaction {
                    UserGroupAssignments.deleteWhere { UserGroupAssignments.groupId eq targetId.toKotlinUuid() }
                    GroupPermissions.deleteWhere { GroupPermissions.groupId eq targetId.toKotlinUuid() }
                    Groups.deleteWhere { Groups.id eq targetId.toKotlinUuid() }
                }
                call.respond(HttpStatusCode.NoContent)
            }

            put("/{id}/permissions", {
                operationId = "setGroupPermissions"
                summary = "Replace group permission set"
                request { pathParameter<String>("id"); body<PutGroupPermissionsRequest>() }
                response {
                    code(HttpStatusCode.OK) { body<GroupResponse>() }
                    code(HttpStatusCode.BadRequest) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.users")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@put
                }
                val targetId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: run { call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found")); return@put }

                val existing = transaction {
                    Groups.selectAll().where { Groups.id eq targetId.toKotlinUuid() }.firstOrNull()
                }
                if (existing == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                    return@put
                }
                if (existing[Groups.isSystem]) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot modify a system group"))
                    return@put
                }
                val req = call.receive<PutGroupPermissionsRequest>()
                val invalid = req.permissions.filter { it !in VALID_PERMISSIONS }
                if (invalid.isNotEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid permission nodes: ${invalid.joinToString()}"))
                    return@put
                }
                transaction {
                    GroupPermissions.deleteWhere { GroupPermissions.groupId eq targetId.toKotlinUuid() }
                    req.permissions.distinct().forEach { perm ->
                        GroupPermissions.insert {
                            it[GroupPermissions.groupId] = targetId.toKotlinUuid()
                            it[GroupPermissions.permission] = perm
                        }
                    }
                }
                val updated = transaction { fetchGroup(targetId) }!!
                call.respond(updated)
            }
        }
    }
}

private fun fetchGroup(id: UUID): GroupResponse? {
    val row = Groups.selectAll().where { Groups.id eq id.toKotlinUuid() }.firstOrNull() ?: return null
    val perms = GroupPermissions.selectAll()
        .where { GroupPermissions.groupId eq id.toKotlinUuid() }
        .map { it[GroupPermissions.permission] }
    return GroupResponse(
        id = row[Groups.id].toString(),
        name = row[Groups.name],
        isSystem = row[Groups.isSystem],
        permissions = perms,
        createdAt = row[Groups.createdAt].toString(),
    )
}

private fun fetchAllGroups(): List<GroupResponse> {
    val allPerms = GroupPermissions.selectAll()
        .groupBy { it[GroupPermissions.groupId] }
        .mapValues { (_, rows) -> rows.map { it[GroupPermissions.permission] } }

    return Groups.selectAll().map { row ->
        val groupId = row[Groups.id]
        GroupResponse(
            id = groupId.toString(),
            name = row[Groups.name],
            isSystem = row[Groups.isSystem],
            permissions = allPerms[groupId] ?: emptyList(),
            createdAt = row[Groups.createdAt].toString(),
        )
    }
}
