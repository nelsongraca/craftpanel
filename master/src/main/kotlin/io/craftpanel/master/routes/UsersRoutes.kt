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

fun Route.usersRoutes(userService: UserService) {
    authenticate(JWT_AUTH) {
        route("/api/users") {

            @KtorResponds(mapping = [ResponseEntry("200", UsersListResponse::class)])
            @KtorDescription(operationId = "listUsers", summary = "List users")
            get("") {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_USERS))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(userService.listUsers())
            }

            @KtorResponds(mapping = [ResponseEntry("201", UserResponse::class)])
            @KtorDescription(operationId = "createUser", summary = "Create user")
            post("") {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_USERS))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<CreateUserRequest>()
                call.respond(HttpStatusCode.Created, userService.createUser(req))
            }

            @KtorResponds(mapping = [ResponseEntry("200", UserResponse::class)])
            @KtorDescription(operationId = "getUser", summary = "Get user")
            get("/{id}") {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_USERS))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                call.respond(userService.getUser(targetId))
            }

            @KtorResponds(mapping = [ResponseEntry("200", UserResponse::class)])
            @KtorDescription(operationId = "updateUser", summary = "Update user")
            patch("/{id}") {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_USERS))
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@patch call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                val req = call.receive<PatchUserRequest>()
                call.respond(userService.updateUser(targetId, req))
            }

            @KtorDescription(operationId = "deleteUser", summary = "Delete user")
            delete("/{id}") {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_USERS))
                    return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                userService.deleteUser(targetId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
