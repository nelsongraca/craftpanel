package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.EnvVarsResponse
import io.craftpanel.master.service.EnvVarsService
import io.craftpanel.master.service.PatchConfigModeRequest
import io.craftpanel.master.service.ProxyBackendListResponse
import io.craftpanel.master.service.ProxyBackendService
import io.craftpanel.master.service.PutEnvVarsRequest
import io.craftpanel.master.service.PutProxyBackendsRequest
import io.craftpanel.master.util.toKotlinUuid
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.github.tabilzad.ktor.annotations.KtorDescription
import java.util.UUID

fun Route.configRoutes(proxyBackendService: ProxyBackendService, envVarsService: EnvVarsService) {
    authenticate(JWT_AUTH) {
        route("/api/servers/{id}/config") {

            @KtorDescription(operationId = "getProxyBackends", summary = "Get proxy backend list")
            get("/proxy") {
                val userId = call.userId()
                val id = parseConfigServerId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = proxyBackendService.getServerScope(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CONFIGURE, serverId = scope.serverIdJava, networkId = scope.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(proxyBackendService.listBackends(id))
            }

            @KtorDescription(operationId = "replaceProxyBackends", summary = "Replace proxy backend list")
            put("/proxy") {
                val userId = call.userId()
                val id = parseConfigServerId(call.parameters["id"])
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = proxyBackendService.getServerScope(id)
                    ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CONFIGURE, serverId = scope.serverIdJava, networkId = scope.networkId))
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<PutProxyBackendsRequest>()
                call.respond(proxyBackendService.replaceBackends(id, req))
            }

            @KtorDescription(operationId = "getEnvVars", summary = "Get server env vars")
            get("/env-vars") {
                val userId = call.userId()
                val id = parseConfigServerId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = envVarsService.getServerScope(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CONFIGURE, serverId = scope.serverIdJava, networkId = scope.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(envVarsService.getEnvVars(id))
            }

            @KtorDescription(operationId = "replaceEnvVars", summary = "Replace server env vars")
            put("/env-vars") {
                val userId = call.userId()
                val id = parseConfigServerId(call.parameters["id"])
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = envVarsService.getServerScope(id)
                    ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CONFIGURE, serverId = scope.serverIdJava, networkId = scope.networkId))
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<PutEnvVarsRequest>()
                call.respond(envVarsService.replaceEnvVars(id, req))
            }

            @KtorDescription(operationId = "updateConfigMode", summary = "Update server config mode")
            put("/mode") {
                val userId = call.userId()
                val id = parseConfigServerId(call.parameters["id"])
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = envVarsService.getServerScope(id)
                    ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CONFIGURE, serverId = scope.serverIdJava, networkId = scope.networkId))
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<PatchConfigModeRequest>()
                call.respond(envVarsService.updateConfigMode(id, req))
            }
        }
    }
}

private fun parseConfigServerId(raw: String?): kotlin.uuid.Uuid? =
    raw?.let {
        runCatching {
            UUID.fromString(it)
                .toKotlinUuid()
        }.getOrNull()
    }
