package io.craftpanel.master.routes

import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.*
import io.craftpanel.master.util.toKotlinUuid
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonObject
import java.util.*

fun Route.serversRoutes(serverService: ServerService) {
    authenticate("auth-jwt") {
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
                if (!PermissionResolver.hasPermission(userId, "server.create"))
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
                if (!PermissionResolver.hasPermission(userId, "server.view", serverId = UUID.fromString(id.toString()), networkId = info.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(serverService.getServer(id))
            }

            patch("/{id}", {
                operationId = "updateServer"
                summary = "Update server"
                request { pathParameter<String>("id"); body<JsonObject>() }
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
                if (!PermissionResolver.hasPermission(userId, "server.configure", serverId = UUID.fromString(id.toString()), networkId = info.networkId))
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val body = call.receive<JsonObject>()
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
                if (!PermissionResolver.hasPermission(userId, "server.delete", serverId = UUID.fromString(id.toString()), networkId = info.networkId))
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
                if (!PermissionResolver.hasPermission(userId, "server.resources", serverId = UUID.fromString(id.toString()), networkId = info.networkId))
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
                if (!PermissionResolver.hasPermission(userId, "server.start", serverId = UUID.fromString(id.toString()), networkId = info.networkId))
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
                if (!PermissionResolver.hasPermission(userId, "server.stop", serverId = UUID.fromString(id.toString()), networkId = info.networkId))
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
                if (!PermissionResolver.hasPermission(userId, "server.restart", serverId = UUID.fromString(id.toString()), networkId = info.networkId))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                serverService.restartServer(id)
                call.respond(HttpStatusCode.Accepted, MessageResponse("Server restart initiated"))
            }

            post("/{id}/upgrade", {
                operationId = "upgradeServer"
                summary = "Upgrade server image"
                request { pathParameter<String>("id"); body<UpgradeServerRequest>() }
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
                if (!PermissionResolver.hasPermission(userId, "server.upgrade", serverId = UUID.fromString(id.toString()), networkId = info.networkId))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<UpgradeServerRequest>()
                serverService.upgradeServer(id, req)
                call.respond(HttpStatusCode.Accepted, MessageResponse("Server upgrade initiated"))
            }

            get("/{id}/metrics", {
                operationId = "getServerMetrics"
                summary = "Get server container metrics"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { body<ContainerMetricsSeriesResponse>() }
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
                if (!PermissionResolver.hasPermission(userId, "server.view", serverId = UUID.fromString(id.toString()), networkId = info.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                    ?.coerceIn(1, 360) ?: 60
                call.respond(serverService.getMetrics(id, limit))
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
                if (!PermissionResolver.hasPermission(userId, "server.configure", serverId = UUID.fromString(id.toString()), networkId = info.networkId))
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<PatchExposureRequest>()
                serverService.updateExposure(id, req)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun parseServerId(raw: String?): kotlin.uuid.Uuid? =
    raw?.let {
        runCatching {
            UUID.fromString(it)
                .toKotlinUuid()
        }.getOrNull()
    }
