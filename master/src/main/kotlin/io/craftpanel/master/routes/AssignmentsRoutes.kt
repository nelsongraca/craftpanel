package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.AssignmentResponse
import io.craftpanel.master.service.AssignmentService
import io.craftpanel.master.service.AssignmentsListResponse
import io.craftpanel.master.service.CreateAssignmentRequest
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.uuid.Uuid

fun Route.assignmentsRoutes(assignmentService: AssignmentService) {
    authenticate(JWT_AUTH) {
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
                val callerId = call.userId()
                if (!PermissionResolver.hasPermission(callerId, Permission.SYSTEM_USERS))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["userId"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                call.respond(assignmentService.listAssignments(targetId))
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
                val callerId = call.userId()
                if (!PermissionResolver.hasPermission(callerId, Permission.SYSTEM_USERS))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["userId"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                val req = call.receive<CreateAssignmentRequest>()
                call.respond(HttpStatusCode.Created, assignmentService.createAssignment(targetId, req))
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
                val callerId = call.userId()
                if (!PermissionResolver.hasPermission(callerId, Permission.SYSTEM_USERS))
                    return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val targetId = call.parameters["userId"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                val assignmentId = call.parameters["assignmentId"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Assignment not found"))
                assignmentService.deleteAssignment(targetId, assignmentId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
