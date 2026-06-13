package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.requirePermission
import io.craftpanel.master.service.PatchSettingsRequest
import io.craftpanel.master.service.SystemService
import io.craftpanel.master.service.SystemSettingsResponse
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.systemRoutes(systemService: SystemService) {
    authenticate(JWT_AUTH) {
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
                call.requirePermission(Permission.SYSTEM_SETTINGS)
                call.respond(systemService.getSettings())
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
                call.requirePermission(Permission.SYSTEM_SETTINGS)
                val userId = call.userId()
                val req = call.receive<PatchSettingsRequest>()
                call.respond(systemService.updateSettings(userId, req))
            }
        }
    }
}
