package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.*
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun Route.serversRoutes(serverService: ServerService) {
    authenticate(JWT_AUTH) {
        route("/api/servers") {

            get("", {
                operationId = "listServers"
                summary = "List servers"
                response {
                    code(HttpStatusCode.OK) { body<List<ServerResponse>>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                call.respond(serverService.listServers(userId))
            }

            post("", {
                operationId = "createServer"
                summary = "Create server"
                request { body<CreateServerRequest>() }
                response {
                    code(HttpStatusCode.Created) { body<ServerResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CREATE))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<CreateServerRequest>()
                call.respond(HttpStatusCode.Created, serverService.createServer(req))
            }

            get("/{id}", {
                operationId = "getServer"
                summary = "Get server"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { body<ServerResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_VIEW, serverId = id, networkId = info.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(serverService.getServer(id))
            }

            patch("/{id}", {
                operationId = "updateServer"
                summary = "Update server"
                request { pathParameter<String>("id"); body<UpdateServerRequest>() }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@patch call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CONFIGURE, serverId = id, networkId = info.networkId))
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val body = call.receive<UpdateServerRequest>()
                serverService.updateServer(id, body)
                call.respond(HttpStatusCode.NoContent)
            }

            delete("/{id}", {
                operationId = "deleteServer"
                summary = "Delete server"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_DELETE, serverId = id, networkId = info.networkId))
                    return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                serverService.deleteServer(id)
                call.respond(HttpStatusCode.NoContent)
            }

            patch("/{id}/resources", {
                operationId = "updateServerResources"
                summary = "Update server resources"
                request { pathParameter<String>("id"); body<PatchResourcesRequest>() }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@patch call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_RESOURCES, serverId = id, networkId = info.networkId))
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<PatchResourcesRequest>()
                serverService.updateResources(id, req)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/{id}/start", {
                operationId = "startServer"
                summary = "Start server"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.Accepted) { body<MessageResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.BadGateway) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_START, serverId = id, networkId = info.networkId))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                serverService.startServer(id)
                call.respond(HttpStatusCode.Accepted, MessageResponse("Server start initiated"))
            }

            post("/{id}/stop", {
                operationId = "stopServer"
                summary = "Stop server"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.Accepted) { body<MessageResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.BadGateway) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_STOP, serverId = id, networkId = info.networkId))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                serverService.stopServer(id)
                call.respond(HttpStatusCode.Accepted, MessageResponse("Server stop initiated"))
            }

            post("/{id}/restart", {
                operationId = "restartServer"
                summary = "Restart server"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.Accepted) { body<MessageResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.BadGateway) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_RESTART, serverId = id, networkId = info.networkId))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                serverService.restartServer(id)
                call.respond(HttpStatusCode.Accepted, MessageResponse("Server restart initiated"))
            }

            get("/{id}/metrics", {
                operationId = "getServerMetrics"
                summary = "Get server container metrics"
                request {
                    pathParameter<String>("id")
                    queryParameter<String>("from") { required = true }
                    queryParameter<String>("to") { required = true }
                }
                response {
                    code(HttpStatusCode.OK) { body<ContainerMetricsSeriesResponse>() }
                    code(HttpStatusCode.BadRequest) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_VIEW, serverId = id, networkId = info.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val from = call.request.queryParameters["from"]?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                } ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("from required (ISO8601)"))
                val to = call.request.queryParameters["to"]?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                } ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("to required (ISO8601)"))
                call.respond(serverService.getMetrics(id, from, to))
            }

            patch("/{id}/exposure", {
                operationId = "updateServerExposure"
                summary = "Update server exposure"
                request { pathParameter<String>("id"); body<PatchExposureRequest>() }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@patch call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CONFIGURE, serverId = id, networkId = info.networkId))
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<PatchExposureRequest>()
                serverService.updateExposure(id, req)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun parseServerId(raw: String?): Uuid? =
    raw?.let {
        runCatching {
            Uuid.parse(it)
                
        }.getOrNull()
    }
