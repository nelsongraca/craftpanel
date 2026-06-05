package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.AlertEventResponse
import io.craftpanel.master.service.AlertService
import io.craftpanel.master.service.AlertThresholdResponse
import io.craftpanel.master.service.CreateAlertThresholdRequest
import kotlinx.serialization.Serializable
import io.craftpanel.master.util.toKotlinUuid
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.github.tabilzad.ktor.annotations.KtorDescription
import io.github.tabilzad.ktor.annotations.responds
import io.github.tabilzad.ktor.annotations.respondsNothing
import java.util.*

@Serializable
data class AlertThresholdsResponse(val thresholds: List<AlertThresholdResponse>)

@Serializable
data class AlertEventsListResponse(val events: List<AlertEventResponse>)

fun Route.alertsRoutes(alertService: AlertService) {
    authenticate(JWT_AUTH) {
        route("/api/alerts") {

            @KtorDescription(operationId = "listAlertThresholds", summary = "List alert thresholds")
            get("/thresholds") {
                responds<AlertThresholdsResponse>(HttpStatusCode.OK)
                responds<ErrorResponse>(HttpStatusCode.Forbidden)
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_SETTINGS))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val scopeType = call.request.queryParameters["scope_type"]
                val scopeId = call.request.queryParameters["scope_id"]
                    ?.let {
                        runCatching {
                            UUID.fromString(it)
                                .toKotlinUuid()
                        }.getOrNull()
                    }
                call.respond(AlertThresholdsResponse(alertService.listThresholds(scopeType, scopeId)))
            }

            @KtorDescription(operationId = "createAlertThreshold", summary = "Create alert threshold")
            post("/thresholds") {
                responds<AlertThresholdResponse>(HttpStatusCode.Created)
                responds<ErrorResponse>(HttpStatusCode.Forbidden)
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_SETTINGS))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<CreateAlertThresholdRequest>()
                call.respond(HttpStatusCode.Created, alertService.createThreshold(req))
            }

            @KtorDescription(operationId = "deleteAlertThreshold", summary = "Delete alert threshold")
            delete("/thresholds/{id}") {
                respondsNothing(HttpStatusCode.NoContent)
                responds<ErrorResponse>(HttpStatusCode.BadRequest)
                responds<ErrorResponse>(HttpStatusCode.Forbidden)
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_SETTINGS))
                    return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val id = call.parameters["id"]
                    ?.let {
                        runCatching {
                            UUID.fromString(it)
                                .toKotlinUuid()
                        }.getOrNull()
                    }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid threshold ID"))
                alertService.deleteThreshold(id)
                call.respond(HttpStatusCode.NoContent)
            }

            @KtorDescription(operationId = "listAlertEvents", summary = "List alert events")
            get("/events") {
                responds<AlertEventsListResponse>(HttpStatusCode.OK)
                responds<ErrorResponse>(HttpStatusCode.Forbidden)
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_SETTINGS))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val scopeType = call.request.queryParameters["scope_type"]
                val scopeId = call.request.queryParameters["scope_id"]
                    ?.let {
                        runCatching {
                            UUID.fromString(it)
                                .toKotlinUuid()
                        }.getOrNull()
                    }
                val activeOnly = call.request.queryParameters["active_only"] == "true"
                call.respond(AlertEventsListResponse(alertService.listEvents(scopeType, scopeId, activeOnly)))
            }
        }
    }
}
