package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.github.tabilzad.ktor.annotations.KtorDescription
import io.github.tabilzad.ktor.annotations.KtorResponds
import io.github.tabilzad.ktor.annotations.ResponseEntry
import java.util.*

fun Route.groupsRoutes(groupService: GroupService) {
    authenticate(JWT_AUTH) {
        route("/api/groups") {

            @KtorResponds(mapping = [ResponseEntry("200", GroupResponse::class, isCollection = true)])
            @KtorDescription(operationId = "listGroups", summary = "List groups")
            get("") {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_USERS))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(groupService.listGroups())
            }

            @KtorResponds(mapping = [ResponseEntry("201", GroupResponse::class)])
            @KtorDescription(operationId = "createGroup", summary = "Create group")
            post("") {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_USERS))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<CreateGroupRequest>()
                call.respond(HttpStatusCode.Created, groupService.createGroup(req))
            }

            @KtorResponds(mapping = [ResponseEntry("200", GroupResponse::class)])
            @KtorDescription(operationId = "getGroup", summary = "Get group")
            get("/{id}") {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_USERS))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                call.respond(groupService.getGroup(targetId))
            }

            @KtorResponds(mapping = [ResponseEntry("200", GroupResponse::class)])
            @KtorDescription(operationId = "updateGroup", summary = "Update group name")
            patch("/{id}") {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_USERS))
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@patch call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                val req = call.receive<PatchGroupRequest>()
                call.respond(groupService.updateGroup(targetId, req))
            }

            @KtorDescription(operationId = "deleteGroup", summary = "Delete group")
            delete("/{id}") {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_USERS))
                    return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                groupService.deleteGroup(targetId)
                call.respond(HttpStatusCode.NoContent)
            }

            @KtorResponds(mapping = [ResponseEntry("200", GroupResponse::class)])
            @KtorDescription(operationId = "setGroupPermissions", summary = "Replace group permission set")
            put("/{id}/permissions") {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_USERS))
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Group not found"))
                val req = call.receive<PutGroupPermissionsRequest>()
                call.respond(groupService.setGroupPermissions(targetId, req))
            }
        }
    }
}
