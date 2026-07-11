package io.craftpanel.master.routes

import io.craftpanel.master.auth.*
import io.craftpanel.master.service.*
import io.github.smiley4.ktoropenapi.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Instant

fun Route.serversRoutes(serverService: ServerService, lifecycleService: ServerLifecycleService, exposureService: ServerExposureService) {
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
                call.requirePermission(Permission.SERVER_CREATE)
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
                val auth = call.requireServerPermission(Permission.SERVER_VIEW)
                call.respond(serverService.getServer(auth.serverId))
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
                serverService.updateServer(auth.serverId, body)
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
                serverService.updateResources(auth.serverId, req)
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
                call.respond(serverService.getMetrics(auth.serverId, from, to))
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
                exposureService.updateExposure(auth.serverId, req)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
