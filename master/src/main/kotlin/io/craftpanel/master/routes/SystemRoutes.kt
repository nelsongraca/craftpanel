package io.craftpanel.master.routes

import io.craftpanel.master.auth.PermissionResolver
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
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, "system.settings"))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
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
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, "system.settings"))
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<PatchSettingsRequest>()
                call.respond(systemService.updateSettings(userId, req))
            }
        }
    }
}
