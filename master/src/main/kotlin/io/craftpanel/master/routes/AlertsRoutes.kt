package io.craftpanel.master.routes

import io.craftpanel.master.auth.*
import io.craftpanel.master.service.*
import io.github.smiley4.ktoropenapi.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

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
                call.requirePermission(Permission.SYSTEM_SETTINGS)
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
                call.requirePermission(Permission.SYSTEM_SETTINGS)
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
                call.requirePermission(Permission.SYSTEM_SETTINGS)
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
                call.requirePermission(Permission.SYSTEM_SETTINGS)
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
