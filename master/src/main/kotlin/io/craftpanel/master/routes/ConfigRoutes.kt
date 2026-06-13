package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.EnvVarsResponse
import io.craftpanel.master.service.EnvVarsService
import io.craftpanel.master.service.PatchConfigModeRequest
import io.craftpanel.master.service.PatchStopCommandRequest
import io.craftpanel.master.service.ProxyBackendListResponse
import io.craftpanel.master.service.ProxyBackendService
import io.craftpanel.master.service.PutEnvVarsRequest
import io.craftpanel.master.service.PutProxyBackendsRequest
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.uuid.Uuid

fun Route.configRoutes(proxyBackendService: ProxyBackendService, envVarsService: EnvVarsService) {
    authenticate(JWT_AUTH) {
        route("/api/servers/{id}/config") {

            get("/proxy", {
                operationId = "getProxyBackends"
                summary = "Get proxy backend list"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { body<ProxyBackendListResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseConfigServerId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = proxyBackendService.getServerScope(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CONFIGURE, serverId = scope.serverId, networkId = scope.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(proxyBackendService.listBackends(id))
            }

            put("/proxy", {
                operationId = "replaceProxyBackends"
                summary = "Replace proxy backend list"
                request {
                    pathParameter<String>("id")
                    body<PutProxyBackendsRequest>()
                }
                response {
                    code(HttpStatusCode.OK) { body<ProxyBackendListResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseConfigServerId(call.parameters["id"])
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = proxyBackendService.getServerScope(id)
                    ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CONFIGURE, serverId = scope.serverId, networkId = scope.networkId))
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<PutProxyBackendsRequest>()
                call.respond(proxyBackendService.replaceBackends(id, req))
            }

            get("/env-vars", {
                operationId = "getEnvVars"
                summary = "Get server env vars"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { body<EnvVarsResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseConfigServerId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = envVarsService.getServerScope(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CONFIGURE, serverId = scope.serverId, networkId = scope.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(envVarsService.getEnvVars(id))
            }

            put("/env-vars", {
                operationId = "replaceEnvVars"
                summary = "Replace server env vars"
                request {
                    pathParameter<String>("id")
                    body<PutEnvVarsRequest>()
                }
                response {
                    code(HttpStatusCode.OK) { body<EnvVarsResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseConfigServerId(call.parameters["id"])
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = envVarsService.getServerScope(id)
                    ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CONFIGURE, serverId = scope.serverId, networkId = scope.networkId))
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<PutEnvVarsRequest>()
                call.respond(envVarsService.replaceEnvVars(id, req))
            }

            patch("/stop-command", {
                operationId = "updateStopCommand"
                summary = "Update server stop command"
                request {
                    pathParameter<String>("id")
                    body<PatchStopCommandRequest>()
                }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.BadRequest) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseConfigServerId(call.parameters["id"])
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = envVarsService.getServerScope(id)
                    ?: return@patch call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CONFIGURE, serverId = scope.serverId, networkId = scope.networkId))
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<PatchStopCommandRequest>()
                envVarsService.updateStopCommand(id, req)
                call.respond(HttpStatusCode.NoContent)
            }

            put("/mode", {
                operationId = "updateConfigMode"
                summary = "Update server config mode"
                request {
                    pathParameter<String>("id")
                    body<PatchConfigModeRequest>()
                }
                response {
                    code(HttpStatusCode.OK) { body<EnvVarsResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.BadRequest) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseConfigServerId(call.parameters["id"])
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = envVarsService.getServerScope(id)
                    ?: return@put call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CONFIGURE, serverId = scope.serverId, networkId = scope.networkId))
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<PatchConfigModeRequest>()
                call.respond(envVarsService.updateConfigMode(id, req))
            }
        }
    }
}

private fun parseConfigServerId(raw: String?): Uuid? =
    raw?.let {
        runCatching { Uuid.parse(it) }.getOrNull()
    }
