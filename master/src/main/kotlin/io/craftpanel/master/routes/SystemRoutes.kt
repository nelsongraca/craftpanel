package io.craftpanel.master.routes

import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.database.schema.SystemSettings
import io.craftpanel.master.util.toKotlinUuid
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID

@Serializable
data class SettingsMap(
    @SerialName("metric_retention_days") val metricRetentionDays: Int,
    @SerialName("default_backup_max_count") val defaultBackupMaxCount: Int,
    @SerialName("default_port_range_start") val defaultPortRangeStart: Int,
    @SerialName("default_port_range_end") val defaultPortRangeEnd: Int,
)

@Serializable
data class SystemSettingsResponse(
    val settings: SettingsMap,
    @SerialName("updated_at") val updatedAt: String?,
    @SerialName("updated_by") val updatedBy: String?,
)

@Serializable
data class PatchSettingsRequest(
    @SerialName("metric_retention_days") val metricRetentionDays: Int? = null,
    @SerialName("default_backup_max_count") val defaultBackupMaxCount: Int? = null,
    @SerialName("default_port_range_start") val defaultPortRangeStart: Int? = null,
    @SerialName("default_port_range_end") val defaultPortRangeEnd: Int? = null,
)

fun Route.systemRoutes() {
    authenticate("auth-jwt") {
        route("/api/system/settings") {

            get("", {
                operationId = "getSystemSettings"
                summary = "Get system settings"
                response {
                    code(HttpStatusCode.OK) { body<SystemSettingsResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.settings")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@get
                }
                call.respond(transaction { loadSettings() })
            }

            patch("", {
                operationId = "updateSystemSettings"
                summary = "Update system settings"
                request { body<PatchSettingsRequest>() }
                response {
                    code(HttpStatusCode.OK) { body<SystemSettingsResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.settings")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@patch
                }
                val req = call.receive<PatchSettingsRequest>()

                val portStart = req.defaultPortRangeStart
                val portEnd = req.defaultPortRangeEnd
                if (portStart != null && portEnd != null && portStart >= portEnd) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("default_port_range_start must be less than default_port_range_end"))
                    return@patch
                }
                if (req.metricRetentionDays != null && req.metricRetentionDays < 1) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("metric_retention_days must be at least 1"))
                    return@patch
                }
                if (req.defaultBackupMaxCount != null && req.defaultBackupMaxCount < 1) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("default_backup_max_count must be at least 1"))
                    return@patch
                }

                val userKotlinId = userId.toKotlinUuid()
                transaction {
                    val updates = buildMap<String, String> {
                        if (req.metricRetentionDays != null) put("metric_retention_days", req.metricRetentionDays.toString())
                        if (req.defaultBackupMaxCount != null) put("default_backup_max_count", req.defaultBackupMaxCount.toString())
                        if (req.defaultPortRangeStart != null) put("default_port_range_start", req.defaultPortRangeStart.toString())
                        if (req.defaultPortRangeEnd != null) put("default_port_range_end", req.defaultPortRangeEnd.toString())
                    }
                    updates.forEach { (k, v) ->
                        SystemSettings.upsert {
                            it[SystemSettings.key] = k
                            it[SystemSettings.value] = v
                            it[SystemSettings.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                            it[SystemSettings.updatedBy] = userKotlinId
                        }
                    }
                }

                // Also validate cross-field constraint using stored values when only one side is provided
                val stored = transaction { loadSettings() }
                val resolvedStart = req.defaultPortRangeStart ?: stored.settings.defaultPortRangeStart
                val resolvedEnd = req.defaultPortRangeEnd ?: stored.settings.defaultPortRangeEnd
                if (resolvedStart >= resolvedEnd) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("default_port_range_start must be less than default_port_range_end"))
                    return@patch
                }

                call.respond(stored)
            }
        }
    }
}

private fun loadSettings(): SystemSettingsResponse {
    val rows = SystemSettings.selectAll().toList()
    val map = rows.associate { it[SystemSettings.key] to it[SystemSettings.value] }
    val latest = rows.maxByOrNull { it[SystemSettings.updatedAt] }
    return SystemSettingsResponse(
        settings = SettingsMap(
            metricRetentionDays = map["metric_retention_days"]?.toIntOrNull() ?: 30,
            defaultBackupMaxCount = map["default_backup_max_count"]?.toIntOrNull() ?: 10,
            defaultPortRangeStart = map["default_port_range_start"]?.toIntOrNull() ?: 25570,
            defaultPortRangeEnd = map["default_port_range_end"]?.toIntOrNull() ?: 26070,
        ),
        updatedAt = latest?.get(SystemSettings.updatedAt)?.toString(),
        updatedBy = latest?.get(SystemSettings.updatedBy)?.toString(),
    )
}
