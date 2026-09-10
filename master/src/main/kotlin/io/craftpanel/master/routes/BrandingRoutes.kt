package io.craftpanel.master.routes

import io.craftpanel.master.service.BrandingService
import io.craftpanel.master.service.SystemService
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PublicConfigResponse(
    @SerialName("app_name") val appName: String,
    @SerialName("has_logo") val hasLogo: Boolean,
    @SerialName("logo_hash") val logoHash: String,
    @SerialName("logo_url") val logoUrl: String = "/api/branding/logo"
)

fun Route.brandingRoutes(systemService: SystemService, brandingService: BrandingService) {
    get("/api/config", {
        operationId = "getPublicConfig"
        summary = "Public branding config (unauthenticated)"
        response {
            code(HttpStatusCode.OK) { body<PublicConfigResponse>() }
        }
    }) {
        val settings = systemService.getSettings()
        call.respond(
            PublicConfigResponse(
                appName = settings.settings.appName,
                hasLogo = brandingService.hasCustomLogo(),
                logoHash = brandingService.getLogoHash()
            )
        )
    }

    get("/api/branding/logo", {
        operationId = "getBrandingLogo"
        summary = "Serve branding logo (unauthenticated)"
        response {
            code(HttpStatusCode.OK) { body<ByteArray>() }
        }
    }) {
        val (bytes, contentType) = brandingService.getLogoData()
        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.response.header(HttpHeaders.ETag, "\"${brandingService.getLogoHash()}\"")
        call.respondBytes(bytes, ContentType.parse(contentType))
    }

    get("/api/branding/logo.svg", {
        operationId = "getBrandingLogoSvg"
        summary = "Serve branding logo as SVG fallback (unauthenticated)"
        response {
            code(HttpStatusCode.OK) { body<ByteArray>() }
        }
    }) {
        val (bytes, _) = brandingService.getLogoData()
        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.response.header(HttpHeaders.ETag, "\"${brandingService.getLogoHash()}\"")
        call.respondBytes(bytes, ContentType.Image.SVG)
    }

    get("/api/branding/icon-192.png", {
        operationId = "getBrandingIcon192"
        summary = "Serve 192x192 PWA icon (unauthenticated)"
        response {
            code(HttpStatusCode.OK) { body<ByteArray>() }
        }
    }) {
        val icon = brandingService.getCachedIcon(192)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.response.header(HttpHeaders.ETag, "\"${brandingService.getLogoHash()}\"")
        call.respondBytes(icon, ContentType.Image.PNG)
    }

    get("/api/branding/icon-512.png", {
        operationId = "getBrandingIcon512"
        summary = "Serve 512x512 PWA icon (unauthenticated)"
        response {
            code(HttpStatusCode.OK) { body<ByteArray>() }
        }
    }) {
        val icon = brandingService.getCachedIcon(512)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=3600")
        call.response.header(HttpHeaders.ETag, "\"${brandingService.getLogoHash()}\"")
        call.respondBytes(icon, ContentType.Image.PNG)
    }
}