package io.craftpanel.master.routes

import io.craftpanel.master.auth.*
import io.craftpanel.master.service.*
import io.github.smiley4.ktoropenapi.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.uuid.Uuid

fun Route.usersRoutes(userService: UserService) {
    authenticate(JWT_AUTH) {
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
                call.requirePermission(Permission.SYSTEM_USERS)
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
                call.requirePermission(Permission.SYSTEM_USERS)
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
                call.requirePermission(Permission.SYSTEM_USERS)
                val targetId = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                call.respond(userService.getUser(targetId))
            }

            patch("/{id}", {
                operationId = "updateUser"
                summary = "Update user"
                request {
                    pathParameter<String>("id")
                    body<PatchUserRequest>()
                }
                response {
                    code(HttpStatusCode.OK) { body<UserResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                call.requirePermission(Permission.SYSTEM_USERS)
                val targetId = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
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
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                call.requirePermission(Permission.SYSTEM_USERS)
                val userId = call.userId()
                val targetId = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                if (userId == targetId) {
                    return@delete call.respond(HttpStatusCode.Conflict, ErrorResponse("Cannot delete yourself"))
                }
                userService.deleteUser(targetId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
