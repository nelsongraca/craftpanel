package io.craftpanel.master.routes

import io.craftpanel.master.service.SystemService
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PublicConfigResponse(
    @SerialName("app_name") val appName: String
)

fun Route.brandingRoutes(systemService: SystemService) {
    get("/api/config", {
        operationId = "getPublicConfig"
        summary = "Public branding config (unauthenticated)"
        response {
            code(HttpStatusCode.OK) { body<PublicConfigResponse>() }
        }
    }) {
        val settings = systemService.getSettings()
        call.respond(PublicConfigResponse(appName = settings.settings.appName))
    }
}
