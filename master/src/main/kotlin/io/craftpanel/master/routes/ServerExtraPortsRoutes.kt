package io.craftpanel.master.routes

import io.craftpanel.master.auth.*
import io.craftpanel.master.routes.dto.*
import io.craftpanel.master.service.NotFoundException
import io.craftpanel.master.service.UnprocessableException
import io.craftpanel.master.service.repo.ServerExtraPortRepository
import io.craftpanel.master.service.repo.ServerRepository
import io.github.smiley4.ktoropenapi.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.uuid.Uuid

fun Route.serverExtraPortsRoutes(serverRepository: ServerRepository, extraPortRepository: ServerExtraPortRepository) {
    authenticate(JWT_AUTH) {
        route("/api/servers/{id}/ports") {
            get("", {
                operationId = "getServerPorts"
                summary = "Get server primary and extra ports"
                request { pathParameter<String>("id") { description = "Server ID" } }
                response {
                    code(HttpStatusCode.OK) { body<ServerPortsResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val authorized = call.requireServerPermission(Permission.SERVER_VIEW)
                val server = serverRepository.findById(authorized.serverId)
                    ?: throw NotFoundException("Server not found")

                val primaryPort = PrimaryPortInfo(
                    hostPort = server.hostPort,
                    containerPort = server.containerListenPort ?: if (server.serverType.isProxy) 25577 else 25565,
                    protocol = server.containerProtocol
                )
                val extraPorts = extraPortRepository.findByServerId(authorized.serverId).map { it.toResponse() }

                call.respond(ServerPortsResponse(primaryPort = primaryPort, extraPorts = extraPorts))
            }

            post("", {
                operationId = "addServerExtraPort"
                summary = "Add an extra port to a server"
                request {
                    pathParameter<String>("id") { description = "Server ID" }
                    body<CreateServerExtraPortRequest>()
                }
                response {
                    code(HttpStatusCode.Created) { body<ServerExtraPortResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val authorized = call.requireServerPermission(Permission.SERVER_CONFIGURE)
                val server = serverRepository.findById(authorized.serverId)
                    ?: throw NotFoundException("Server not found")

                val req = call.receive<CreateServerExtraPortRequest>()
                if (req.name.isBlank()) throw UnprocessableException("Port name cannot be blank")
                if (req.containerPort <= 0 || req.containerPort > 65535) throw UnprocessableException("Invalid container port")

                val created = extraPortRepository.createExtraPort(
                    serverId = authorized.serverId,
                    nodeId = server.nodeId,
                    name = req.name.trim(),
                    containerPort = req.containerPort,
                    hostPort = req.hostPort,
                    protocol = req.protocol
                )

                call.respond(HttpStatusCode.Created, created.toResponse())
            }

            delete("/{portId}", {
                operationId = "deleteServerExtraPort"
                summary = "Delete an extra port from a server"
                request {
                    pathParameter<String>("id") { description = "Server ID" }
                    pathParameter<String>("portId") { description = "Port ID" }
                }
                response {
                    code(HttpStatusCode.NoContent) {}
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val authorized = call.requireServerPermission(Permission.SERVER_CONFIGURE)
                val portIdStr = call.parameters["portId"] ?: throw UnprocessableException("Missing port id")
                val portId = runCatching { Uuid.parse(portIdStr) }.getOrNull()
                    ?: throw UnprocessableException("Invalid port id")

                val deleted = extraPortRepository.deleteExtraPort(portId)
                if (!deleted) throw NotFoundException("Extra port not found")

                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
