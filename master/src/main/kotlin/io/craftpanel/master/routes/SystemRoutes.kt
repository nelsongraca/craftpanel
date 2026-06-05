package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.PatchSettingsRequest
import io.craftpanel.master.service.SystemService
import io.craftpanel.master.service.SystemSettingsResponse
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.github.tabilzad.ktor.annotations.KtorDescription
import io.github.tabilzad.ktor.annotations.KtorResponds
import io.github.tabilzad.ktor.annotations.ResponseEntry

fun Route.systemRoutes(systemService: SystemService) {
    authenticate(JWT_AUTH) {
        route("/api/system/settings") {

            @KtorResponds(mapping = [ResponseEntry("200", SystemSettingsResponse::class)])
            @KtorDescription(operationId = "getSystemSettings", summary = "Get system settings")
            get("") {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_SETTINGS))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(systemService.getSettings())
            }

            @KtorResponds(mapping = [ResponseEntry("200", SystemSettingsResponse::class)])
            @KtorDescription(operationId = "updateSystemSettings", summary = "Update system settings")
            patch("") {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_SETTINGS))
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<PatchSettingsRequest>()
                call.respond(systemService.updateSettings(userId, req))
            }
        }
    }
}
