package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.AssignmentResponse
import io.craftpanel.master.service.AssignmentService
import io.craftpanel.master.service.AssignmentsListResponse
import io.craftpanel.master.service.CreateAssignmentRequest
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.github.tabilzad.ktor.annotations.KtorDescription
import java.util.*

fun Route.assignmentsRoutes(assignmentService: AssignmentService) {
    authenticate(JWT_AUTH) {
        route("/api/users/{userId}/assignments") {

            @KtorDescription(operationId = "listUserAssignments", summary = "List group assignments for a user")
            get("") {
                val callerId = call.userId()
                if (!PermissionResolver.hasPermission(callerId, Permission.SYSTEM_USERS))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                call.respond(assignmentService.listAssignments(targetId))
            }

            @KtorDescription(operationId = "createAssignment", summary = "Add a group assignment to a user")
            post("") {
                val callerId = call.userId()
                if (!PermissionResolver.hasPermission(callerId, Permission.SYSTEM_USERS))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                val req = call.receive<CreateAssignmentRequest>()
                call.respond(HttpStatusCode.Created, assignmentService.createAssignment(targetId, req))
            }

            @KtorDescription(operationId = "deleteAssignment", summary = "Remove a group assignment from a user")
            delete("/{assignmentId}") {
                val callerId = call.userId()
                if (!PermissionResolver.hasPermission(callerId, Permission.SYSTEM_USERS))
                    return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["userId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                val assignmentId = call.parameters["assignmentId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Assignment not found"))
                assignmentService.deleteAssignment(targetId, assignmentId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
