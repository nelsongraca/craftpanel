package io.craftpanel.master.routes

import io.craftpanel.master.auth.*
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.routes.dto.*
import io.craftpanel.master.service.*
import io.craftpanel.master.service.repo.isExpired
import io.github.smiley4.ktoropenapi.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun Route.serversRoutes(
    serverService: ServerService,
    queryService: ServerQueryService,
    lifecycleService: ServerLifecycleService,
    exposureService: ServerExposureService,
    serverExposure: ServerExposure
) {
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
                val rows = queryService.listServers(userId)
                val migratingIds = if (rows.isEmpty()) emptySet()
                else rows.filter { queryService.isMigrating(it.id) }
                    .map { it.id }
                    .toSet()
                call.respond(rows.map { it.toResponse(serverExposure, it.id in migratingIds) })
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
                call.requirePermission(Permission.SERVER_CREATE)
                val req = call.receive<CreateServerRequest>()
                if (req.expiresAt != null) call.requirePermission(Permission.SERVER_EXPIRES)
                val row = serverService.createServer(
                    name = req.name,
                    displayName = req.displayName,
                    description = req.description,
                    nodeId = req.nodeId,
                    networkId = req.networkId,
                    serverType = req.serverType,
                    mcVersion = req.mcVersion,
                    itzgImageTag = req.itzgImageTag,
                    memoryMb = req.memoryMb,
                    cpuShares = req.cpuShares,
                    expiresAt = req.expiresAt,
                    customServerJar = req.customServerJar,
                    containerListenPort = req.containerListenPort,
                    containerProtocol = req.containerProtocol,
                    disableHealthcheck = req.disableHealthcheck,
                    forceRedownload = req.forceRedownload
                )
                call.respond(HttpStatusCode.Created, row.toResponse(serverExposure, false))
            }

            post("/{id}/clone", {
                operationId = "cloneServer"
                summary = "Clone a server's configuration under a new name"
                description =
                    "Copies display name, description, server type/loader, Minecraft version, node, network, resources, environment variables, and mods/plugins. World/disk data is not copied."
                request {
                    pathParameter<String>("id")
                    body<CloneServerRequest>()
                }
                response {
                    code(HttpStatusCode.Created) { body<ServerResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                call.requirePermission(Permission.SERVER_CREATE)
                val sourceId = call.parameters["id"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?: throw UnprocessableException("Invalid server id")
                val req = call.receive<CloneServerRequest>()
                val row = serverService.cloneServer(sourceId, req.name, req.displayName, req.description)
                call.respond(HttpStatusCode.Created, row.toResponse(serverExposure, false))
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
                val auth = call.requireServerPermission(Permission.SERVER_VIEW)
                val row = queryService.getServer(auth.serverId)
                call.respond(row.toResponse(serverExposure, queryService.isMigrating(auth.serverId)))
            }

            patch("/{id}", {
                operationId = "updateServer"
                summary = "Update server"
                request {
                    pathParameter<String>("id")
                    body<UpdateServerRequest>()
                }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_CONFIGURE)
                val body = call.receive<UpdateServerRequest>()
                serverService.updateServer(
                    auth.serverId,
                    body.displayName,
                    body.description,
                    body.networkId,
                    body.mcVersion,
                    body.itzgImageTag,
                    customServerJar = body.customServerJar,
                    containerListenPort = body.containerListenPort,
                    containerProtocol = body.containerProtocol,
                    disableHealthcheck = body.disableHealthcheck,
                    forceRedownload = body.forceRedownload
                )
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
                val auth = call.requireServerPermission(Permission.SERVER_DELETE)
                serverService.deleteServer(auth.serverId)
                call.respond(HttpStatusCode.NoContent)
            }

            patch("/{id}/resources", {
                operationId = "updateServerResources"
                summary = "Update server resources"
                request {
                    pathParameter<String>("id")
                    body<PatchResourcesRequest>()
                }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_RESOURCES)
                val req = call.receive<PatchResourcesRequest>()
                serverService.updateResources(auth.serverId, req.memoryMb, req.cpuShares, req.itzgImageTag)
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
                val auth = call.requireServerPermission(Permission.SERVER_START)
                lifecycleService.startServer(auth.serverId)
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
                val auth = call.requireServerPermission(Permission.SERVER_STOP)
                lifecycleService.stopServer(auth.serverId)
                call.respond(HttpStatusCode.Accepted, MessageResponse("Server stop initiated"))
            }

            post("/{id}/force-stop", {
                operationId = "forceStopServer"
                summary = "Force stop server (immediate container kill)"
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
                val auth = call.requireServerPermission(Permission.SERVER_FORCE_STOP)
                lifecycleService.forceStopServer(auth.serverId)
                call.respond(HttpStatusCode.Accepted, MessageResponse("Force stop initiated"))
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
                val auth = call.requireServerPermission(Permission.SERVER_RESTART)
                lifecycleService.restartServer(auth.serverId)
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
                val auth = call.requireServerPermission(Permission.SERVER_VIEW)
                val from = call.request.queryParameters["from"]?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                } ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("from required (ISO8601)"))
                val to = call.request.queryParameters["to"]?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                } ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("to required (ISO8601)"))
                call.respond(queryService.getMetrics(auth.serverId, from, to))
            }

            patch("/{id}/exposure", {
                operationId = "updateServerExposure"
                summary = "Update server exposure"
                request {
                    pathParameter<String>("id")
                    body<PatchExposureRequest>()
                }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_CONFIGURE)
                val req = call.receive<PatchExposureRequest>()
                exposureService.updateExposure(auth.serverId, req.exposedExternally, req.publicSubdomain, req.customHostname)
                call.respond(HttpStatusCode.NoContent)
            }

            patch("/{id}/expiration", {
                operationId = "updateServerExpiration"
                summary = "Update server expiration date. Once the date is reached the server can no longer be started and any running instance is stopped."
                request {
                    pathParameter<String>("id")
                    body<PatchExpirationRequest>()
                }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_EXPIRES)
                val req = call.receive<PatchExpirationRequest>()
                val row = serverService.updateExpiration(auth.serverId, req.expiresAt)
                if (row.isExpired() && ServerStatus.fromDb(row.status).isRunning) {
                    lifecycleService.stopServer(auth.serverId)
                }
                call.respond(HttpStatusCode.NoContent)
            }

            patch("/{id}/disabled", {
                operationId = "setServerDisabled"
                summary = "Enable or disable a server. Disabling stops a running server immediately and prevents it being started until re-enabled."
                request {
                    pathParameter<String>("id")
                    body<PatchDisabledRequest>()
                }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val auth = call.requireServerPermission(Permission.SERVER_DISABLE)
                val req = call.receive<PatchDisabledRequest>()
                val row = serverService.updateDisabled(auth.serverId, req.disabled)
                if (req.disabled && ServerStatus.fromDb(row.status).isRunning) {
                    lifecycleService.stopServer(auth.serverId)
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
