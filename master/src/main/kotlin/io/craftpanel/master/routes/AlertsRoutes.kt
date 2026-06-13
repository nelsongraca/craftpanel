package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.AlertEventResponse
import io.craftpanel.master.service.AlertService
import io.craftpanel.master.service.AlertThresholdResponse
import io.craftpanel.master.service.CreateAlertThresholdRequest
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*


@Serializable
data class AlertThresholdsResponse(val thresholds: List<AlertThresholdResponse>)

@Serializable
data class AlertEventsListResponse(val events: List<AlertEventResponse>)

fun Route.alertsRoutes(alertService: AlertService) {
    authenticate(JWT_AUTH) {
        route("/api/alerts") {

            get("/thresholds", {
                operationId = "listAlertThresholds"
                summary = "List alert thresholds"
                response {
                    code(HttpStatusCode.OK) { body<AlertThresholdsResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_SETTINGS))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val scopeType = call.request.queryParameters["scope_type"]
                val scopeId = call.request.queryParameters["scope_id"]
                    ?.let {
                        runCatching {
                            Uuid.parse(it)
                                
                        }.getOrNull()
                    }
                call.respond(AlertThresholdsResponse(alertService.listThresholds(scopeType, scopeId)))
            }

            post("/thresholds", {
                operationId = "createAlertThreshold"
                summary = "Create alert threshold"
                request { body<CreateAlertThresholdRequest>() }
                response {
                    code(HttpStatusCode.Created) { body<AlertThresholdResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_SETTINGS))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<CreateAlertThresholdRequest>()
                call.respond(HttpStatusCode.Created, alertService.createThreshold(req))
            }

            delete("/thresholds/{id}", {
                operationId = "deleteAlertThreshold"
                summary = "Delete alert threshold"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_SETTINGS))
                    return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val id = call.parameters["id"]
                    ?.let {
                        runCatching {
                            Uuid.parse(it)
                                
                        }.getOrNull()
                    }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid threshold ID"))
                alertService.deleteThreshold(id)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/events", {
                operationId = "listAlertEvents"
                summary = "List alert events"
                response {
                    code(HttpStatusCode.OK) { body<Map<String, List<AlertEventResponse>>>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_SETTINGS))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val scopeType = call.request.queryParameters["scope_type"]
                val scopeId = call.request.queryParameters["scope_id"]
                    ?.let {
                        runCatching {
                            Uuid.parse(it)
                                
                        }.getOrNull()
                    }
                val activeOnly = call.request.queryParameters["active_only"] == "true"
                call.respond(AlertEventsListResponse(alertService.listEvents(scopeType, scopeId, activeOnly)))
            }
        }
    }
}
