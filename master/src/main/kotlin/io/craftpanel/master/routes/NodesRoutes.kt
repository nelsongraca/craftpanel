package io.craftpanel.master.routes

import io.craftpanel.master.auth.*
import io.craftpanel.master.service.*
import io.github.smiley4.ktoropenapi.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.uuid.Uuid

fun Route.nodesRoutes(nodeService: NodeService) {
    authenticate(JWT_AUTH) {
        route("/api/nodes") {
            get("", {
                operationId = "listNodes"
                summary = "List nodes"
                response {
                    code(HttpStatusCode.OK) { body<List<NodeResponse>>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                call.requirePermission(Permission.SYSTEM_NODES)
                call.respond(nodeService.listNodes())
            }

            get("/{id}", {
                operationId = "getNode"
                summary = "Get node"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { body<NodeResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                call.requirePermission(Permission.SYSTEM_NODES)
                val id = parseNodeId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid node ID"))
                call.respond(nodeService.getNode(id))
            }

            post("/{id}/trust", {
                operationId = "trustNode"
                summary = "Trust node"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                call.requirePermission(Permission.SYSTEM_NODES)
                val id = parseNodeId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid node ID"))
                nodeService.trustNode(id)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/{id}/reject", {
                operationId = "rejectNode"
                summary = "Reject node"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                call.requirePermission(Permission.SYSTEM_NODES)
                val id = parseNodeId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid node ID"))
                nodeService.rejectNode(id)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/{id}/token/rotate", {
                operationId = "rotateNodeToken"
                summary = "Rotate node token"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { body<NodeKeyResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                call.requirePermission(Permission.SYSTEM_NODES)
                val id = parseNodeId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid node ID"))
                call.respond(NodeKeyResponse(nodeService.rotateToken(id)))
            }

            post("/{id}/shutdown", {
                operationId = "shutdownNode"
                summary = "Shutdown node agent"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.Accepted) { body<MessageResponse>() }
                    code(HttpStatusCode.BadGateway) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                call.requirePermission(Permission.SYSTEM_NODES)
                val id = parseNodeId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid node ID"))
                nodeService.shutdownNode(id)
                call.respond(HttpStatusCode.Accepted, MessageResponse("Shutdown command sent"))
            }

            patch("/{id}", {
                operationId = "updateNode"
                summary = "Update node"
                request {
                    pathParameter<String>("id")
                    body<PatchNodeRequest>()
                }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                call.requirePermission(Permission.SYSTEM_NODES)
                val id = parseNodeId(call.parameters["id"])
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid node ID"))
                val req = call.receive<PatchNodeRequest>()
                nodeService.updateNode(id, req)
                call.respond(HttpStatusCode.NoContent)
            }

            delete("/{id}", {
                operationId = "decommissionNode"
                summary = "Decommission node"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                call.requirePermission(Permission.SYSTEM_NODES)
                val id = parseNodeId(call.parameters["id"])
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid node ID"))
                nodeService.decommissionNode(id)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/{id}/metrics", {
                operationId = "getNodeMetrics"
                summary = "Get node metrics"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { body<NodeMetricsResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                call.requirePermission(Permission.SYSTEM_NODES)
                val id = parseNodeId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid node ID"))
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                    ?.coerceIn(1, 360) ?: 60
                call.respond(nodeService.getNodeMetrics(id, limit))
            }
        }
    }
}

private fun parseNodeId(raw: String?): Uuid? = raw?.let {
    runCatching {
        Uuid.parse(it)
    }.getOrNull()
}
