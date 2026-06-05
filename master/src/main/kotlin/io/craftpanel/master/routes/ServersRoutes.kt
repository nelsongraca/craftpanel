package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.*
import io.craftpanel.master.util.toKotlinUuid
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.github.tabilzad.ktor.annotations.KtorDescription
import io.github.tabilzad.ktor.annotations.KtorResponds
import io.github.tabilzad.ktor.annotations.ResponseEntry
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import java.util.*

fun Route.serversRoutes(serverService: ServerService) {
    authenticate(JWT_AUTH) {
        route("/api/servers") {

            @KtorResponds(mapping = [ResponseEntry("200", ServerResponse::class, isCollection = true)])
            @KtorDescription(operationId = "listServers", summary = "List servers")
            get("") {
                val userId = call.userId()
                call.respond(serverService.listServers(userId))
            }

            @KtorResponds(mapping = [ResponseEntry("201", ServerResponse::class)])
            @KtorDescription(operationId = "createServer", summary = "Create server")
            post("") {
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CREATE))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<CreateServerRequest>()
                call.respond(HttpStatusCode.Created, serverService.createServer(req))
            }

            @KtorResponds(mapping = [ResponseEntry("200", ServerResponse::class)])
            @KtorDescription(operationId = "getServer", summary = "Get server")
            get("/{id}") {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_VIEW, serverId = UUID.fromString(id.toString()), networkId = info.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(serverService.getServer(id))
            }

            @KtorDescription(operationId = "updateServer", summary = "Update server")
            patch("/{id}") {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@patch call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CONFIGURE, serverId = UUID.fromString(id.toString()), networkId = info.networkId))
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val body = call.receive<JsonObject>()
                serverService.updateServer(id, body)
                call.respond(HttpStatusCode.NoContent)
            }

            @KtorDescription(operationId = "deleteServer", summary = "Delete server")
            delete("/{id}") {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_DELETE, serverId = UUID.fromString(id.toString()), networkId = info.networkId))
                    return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                serverService.deleteServer(id)
                call.respond(HttpStatusCode.NoContent)
            }

            @KtorDescription(operationId = "updateServerResources", summary = "Update server resources")
            patch("/{id}/resources") {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@patch call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_RESOURCES, serverId = UUID.fromString(id.toString()), networkId = info.networkId))
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<PatchResourcesRequest>()
                serverService.updateResources(id, req)
                call.respond(HttpStatusCode.NoContent)
            }

            @KtorResponds(mapping = [ResponseEntry("202", MessageResponse::class)])
            @KtorDescription(operationId = "startServer", summary = "Start server")
            post("/{id}/start") {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_START, serverId = UUID.fromString(id.toString()), networkId = info.networkId))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                serverService.startServer(id)
                call.respond(HttpStatusCode.Accepted, MessageResponse("Server start initiated"))
            }

            @KtorResponds(mapping = [ResponseEntry("202", MessageResponse::class)])
            @KtorDescription(operationId = "stopServer", summary = "Stop server")
            post("/{id}/stop") {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_STOP, serverId = UUID.fromString(id.toString()), networkId = info.networkId))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                serverService.stopServer(id)
                call.respond(HttpStatusCode.Accepted, MessageResponse("Server stop initiated"))
            }

            @KtorResponds(mapping = [ResponseEntry("202", MessageResponse::class)])
            @KtorDescription(operationId = "restartServer", summary = "Restart server")
            post("/{id}/restart") {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_RESTART, serverId = UUID.fromString(id.toString()), networkId = info.networkId))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                serverService.restartServer(id)
                call.respond(HttpStatusCode.Accepted, MessageResponse("Server restart initiated"))
            }

            @KtorResponds(mapping = [ResponseEntry("202", MessageResponse::class)])
            @KtorDescription(operationId = "upgradeServer", summary = "Upgrade server image")
            post("/{id}/upgrade") {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_UPGRADE, serverId = UUID.fromString(id.toString()), networkId = info.networkId))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<UpgradeServerRequest>()
                serverService.upgradeServer(id, req)
                call.respond(HttpStatusCode.Accepted, MessageResponse("Server upgrade initiated"))
            }

            @KtorResponds(mapping = [ResponseEntry("200", ContainerMetricsSeriesResponse::class)])
            @KtorDescription(operationId = "getServerMetrics", summary = "Get server container metrics")
            get("/{id}/metrics") {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_VIEW, serverId = UUID.fromString(id.toString()), networkId = info.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val from = call.request.queryParameters["from"]?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                } ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("from required (ISO8601)"))
                val to = call.request.queryParameters["to"]?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                } ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("to required (ISO8601)"))
                call.respond(serverService.getMetrics(id, from, to))
            }

            @KtorDescription(operationId = "updateServerExposure", summary = "Update server exposure")
            patch("/{id}/exposure") {
                val userId = call.userId()
                val id = parseServerId(call.parameters["id"])
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val info = serverService.authInfo(id)
                    ?: return@patch call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_CONFIGURE, serverId = UUID.fromString(id.toString()), networkId = info.networkId))
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
