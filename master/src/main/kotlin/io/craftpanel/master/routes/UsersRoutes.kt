package io.craftpanel.master.routes

import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.CreateUserRequest
import io.craftpanel.master.service.PatchUserRequest
import io.craftpanel.master.service.UserService
import io.craftpanel.master.service.UsersListResponse
import io.craftpanel.master.service.UserResponse
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import java.util.UUID

fun Route.usersRoutes(userService: UserService) {
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
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, "system.users"))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(userService.listUsers())
            }

            post("", {
                operationId = "createUser"
                summary = "Create user"
                request { body<CreateUserRequest>() }
                response {
                    code(HttpStatusCode.Created) { body<UserResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, "system.users"))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<CreateUserRequest>()
                call.respond(HttpStatusCode.Created, userService.createUser(req))
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
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, "system.users"))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                call.respond(userService.getUser(targetId))
            }

            patch("/{id}", {
                operationId = "updateUser"
                summary = "Update user"
                request { pathParameter<String>("id"); body<PatchUserRequest>() }
                response {
                    code(HttpStatusCode.OK) { body<UserResponse>() }
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
                    ?: return@patch call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                val req = call.receive<PatchUserRequest>()
                call.respond(userService.updateUser(targetId, req))
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
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, "system.users"))
                    return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                userService.deleteUser(targetId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
