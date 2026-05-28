package io.craftpanel.master.routes

import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.*
import io.github.smiley4.ktoropenapi.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*

fun Route.groupsRoutes(groupService: GroupService) {
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
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, "system.users"))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(groupService.listGroups())
            }

            post("", {
                operationId = "createGroup"
                summary = "Create group"
                request { body<CreateGroupRequest>() }
                response {
                    code(HttpStatusCode.Created) { body<GroupResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, "system.users"))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<CreateGroupRequest>()
                call.respond(HttpStatusCode.Created, groupService.createGroup(req))
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
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, "system.users"))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                call.respond(groupService.getGroup(targetId))
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
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, "system.users"))
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@patch call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                val req = call.receive<PatchGroupRequest>()
                call.respond(groupService.updateGroup(targetId, req))
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
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, "system.users"))
                    return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                groupService.deleteGroup(targetId)
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
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, "system.users"))
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                val req = call.receive<PutGroupPermissionsRequest>()
                call.respond(groupService.setGroupPermissions(targetId, req))
            }
        }
    }
}
