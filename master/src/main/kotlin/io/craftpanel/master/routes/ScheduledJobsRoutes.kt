package io.craftpanel.master.routes

import io.craftpanel.master.auth.*
import io.craftpanel.master.domain.ScheduledJobType
import io.craftpanel.master.service.*
import io.github.smiley4.ktoropenapi.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class JobTypesResponse(val types: List<ScheduledJobType>)

/** The action permission a user must additionally hold to schedule a job of this type. */
private fun ScheduledJobType.requiredPermission(): Permission = when (this) {
    ScheduledJobType.START -> Permission.SERVER_START
    ScheduledJobType.STOP -> Permission.SERVER_STOP
    ScheduledJobType.RESTART -> Permission.SERVER_RESTART
    ScheduledJobType.RCON_COMMAND -> Permission.SERVER_CONSOLE
}

fun Route.scheduledJobsRoutes(jobService: ScheduledJobService) {
    authenticate(JWT_AUTH) {
        route("/api/servers/{id}/jobs") {
            get("", {
                operationId = "listScheduledJobs"
                summary = "List scheduled jobs for a server"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { body<List<ScheduledJobResponse>>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_CRON)
                call.respond(jobService.list(auth.serverId))
            }

            post("", {
                operationId = "createScheduledJob"
                summary = "Create a scheduled job for a server"
                request {
                    pathParameter<String>("id")
                    body<CreateScheduledJobRequest>()
                }
                response {
                    code(HttpStatusCode.Created) { body<ScheduledJobResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_CRON)
                val req = call.receive<CreateScheduledJobRequest>()
                // Scheduling an action requires the action's own permission, not just server.cron.
                call.requireServerPermission(auth.serverId, req.type.requiredPermission())
                call.respond(HttpStatusCode.Created, jobService.create(auth.serverId, req))
            }

            patch("/{jobId}", {
                operationId = "updateScheduledJob"
                summary = "Update a scheduled job"
                request {
                    pathParameter<String>("id")
                    pathParameter<String>("jobId")
                    body<UpdateScheduledJobRequest>()
                }
                response {
                    code(HttpStatusCode.OK) { body<ScheduledJobResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_CRON)
                val jobId = call.parseJobId() ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid job ID"))
                val req = call.receive<UpdateScheduledJobRequest>()
                call.respond(jobService.update(auth.serverId, jobId, req))
            }

            delete("/{jobId}", {
                operationId = "deleteScheduledJob"
                summary = "Delete a scheduled job"
                request {
                    pathParameter<String>("id")
                    pathParameter<String>("jobId")
                }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_CRON)
                val jobId = call.parseJobId() ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid job ID"))
                jobService.delete(auth.serverId, jobId)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        get("/api/system/job-types", {
            operationId = "listJobTypes"
            summary = "List schedulable job types"
            response {
                code(HttpStatusCode.OK) { body<JobTypesResponse>() }
                code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
            }
        }) {
            call.respond(JobTypesResponse(jobService.availableTypes()))
        }
    }
}

private fun ApplicationCall.parseJobId(): Uuid? = parameters["jobId"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
