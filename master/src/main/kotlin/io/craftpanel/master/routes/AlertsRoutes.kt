package io.craftpanel.master.routes

import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.database.schema.AlertEvents
import io.craftpanel.master.database.schema.AlertThresholds
import io.craftpanel.master.util.toKotlinUuid
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

@Serializable
data class AlertThresholdResponse(
    val id: String,
    @SerialName("scope_type") val scopeType: String,
    @SerialName("scope_id") val scopeId: String,
    val metric: String,
    @SerialName("threshold_value") val thresholdValue: Double? = null,
    @SerialName("threshold_state") val thresholdState: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class AlertEventResponse(
    val id: String,
    @SerialName("threshold_id") val thresholdId: String,
    val message: String,
    @SerialName("fired_at") val firedAt: String,
    @SerialName("resolved_at") val resolvedAt: String? = null,
)

@Serializable
data class CreateAlertThresholdRequest(
    @SerialName("scope_type") val scopeType: String,
    @SerialName("scope_id") val scopeId: String,
    val metric: String,
    @SerialName("threshold_value") val thresholdValue: Double? = null,
    @SerialName("threshold_state") val thresholdState: String? = null,
)

fun Route.alertsRoutes() {
    authenticate("auth-jwt") {
        route("/api/alerts") {

            get("/thresholds", {
                operationId = "listAlertThresholds"
                summary = "List alert thresholds"
                response {
                    code(HttpStatusCode.OK) { body<Map<String, List<AlertThresholdResponse>>>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.settings")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@get
                }

                val scopeType = call.request.queryParameters["scope_type"]
                val scopeId = call.request.queryParameters["scope_id"]
                    ?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }

                val thresholds = transaction {
                    AlertThresholds.selectAll().apply {
                        if (scopeType != null) where { AlertThresholds.scopeType eq scopeType }
                        if (scopeId != null) where { AlertThresholds.scopeId eq scopeId }
                    }.map { row ->
                        AlertThresholdResponse(
                            id = row[AlertThresholds.id].toString(),
                            scopeType = row[AlertThresholds.scopeType],
                            scopeId = row[AlertThresholds.scopeId].toString(),
                            metric = row[AlertThresholds.metric],
                            thresholdValue = row[AlertThresholds.thresholdValue],
                            thresholdState = row[AlertThresholds.thresholdState],
                            createdAt = row[AlertThresholds.createdAt].toString(),
                        )
                    }
                }
                call.respond(mapOf("thresholds" to thresholds))
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
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.settings")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@post
                }

                val req = call.receive<CreateAlertThresholdRequest>()

                if ((req.thresholdValue == null) == (req.thresholdState == null)) {
                    call.respond(
                        HttpStatusCode.UnprocessableEntity,
                        ErrorResponse("Exactly one of threshold_value or threshold_state must be provided")
                    )
                    return@post
                }

                if (req.scopeType !in setOf("NODE", "SERVER")) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("scope_type must be NODE or SERVER"))
                    return@post
                }

                val scopeKotlinId = runCatching { UUID.fromString(req.scopeId).toKotlinUuid() }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Invalid scope_id"))
                    return@post
                }

                val threshold = transaction {
                    val id = AlertThresholds.insert {
                        it[AlertThresholds.scopeType] = req.scopeType
                        it[AlertThresholds.scopeId] = scopeKotlinId
                        it[AlertThresholds.metric] = req.metric
                        it[AlertThresholds.thresholdValue] = req.thresholdValue
                        it[AlertThresholds.thresholdState] = req.thresholdState
                    }[AlertThresholds.id]

                    AlertThresholds.selectAll().where { AlertThresholds.id eq id }.first().let { row ->
                        AlertThresholdResponse(
                            id = row[AlertThresholds.id].toString(),
                            scopeType = row[AlertThresholds.scopeType],
                            scopeId = row[AlertThresholds.scopeId].toString(),
                            metric = row[AlertThresholds.metric],
                            thresholdValue = row[AlertThresholds.thresholdValue],
                            thresholdState = row[AlertThresholds.thresholdState],
                            createdAt = row[AlertThresholds.createdAt].toString(),
                        )
                    }
                }
                call.respond(HttpStatusCode.Created, threshold)
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
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.settings")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@delete
                }

                val id = call.parameters["id"]
                    ?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid threshold ID"))
                        return@delete
                    }

                val deleted = transaction {
                    AlertEvents.deleteWhere { AlertEvents.thresholdId eq id }
                    AlertThresholds.deleteWhere { AlertThresholds.id eq id }
                }
                if (deleted == 0) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Threshold not found"))
                    return@delete
                }
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
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.settings")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@get
                }

                val scopeType = call.request.queryParameters["scope_type"]
                val scopeId = call.request.queryParameters["scope_id"]
                    ?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }
                val activeOnly = call.request.queryParameters["active_only"] == "true"

                val events = transaction {
                    (AlertEvents innerJoin AlertThresholds)
                        .selectAll()
                        .apply {
                            if (scopeType != null) where { AlertThresholds.scopeType eq scopeType }
                            if (scopeId != null) where { AlertThresholds.scopeId eq scopeId }
                            if (activeOnly) where { AlertEvents.resolvedAt.isNull() }
                        }
                        .orderBy(AlertEvents.firedAt, SortOrder.DESC)
                        .map { row ->
                            AlertEventResponse(
                                id = row[AlertEvents.id].toString(),
                                thresholdId = row[AlertEvents.thresholdId].toString(),
                                message = row[AlertEvents.message],
                                firedAt = row[AlertEvents.firedAt].toString(),
                                resolvedAt = row[AlertEvents.resolvedAt]?.toString(),
                            )
                        }
                }
                call.respond(mapOf("events" to events))
            }
        }
    }
}
