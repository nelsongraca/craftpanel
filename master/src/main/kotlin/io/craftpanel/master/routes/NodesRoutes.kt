package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.NodeMetricsResponse
import io.craftpanel.master.service.NodeResponse
import io.craftpanel.master.service.NodeService
import io.craftpanel.master.service.PatchNodeRequest
import io.craftpanel.master.util.toKotlinUuid
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.github.tabilzad.ktor.annotations.KtorDescription
import io.github.tabilzad.ktor.annotations.KtorResponds
import io.github.tabilzad.ktor.annotations.ResponseEntry
import io.github.tabilzad.ktor.annotations.responds
import io.github.tabilzad.ktor.annotations.respondsNothing
import java.util.*

fun Route.nodesRoutes(nodeService: NodeService) {
    authenticate(JWT_AUTH) {
        route("/api/nodes") {

            @KtorResponds(mapping = [ResponseEntry("200", NodeResponse::class, isCollection = true)])
            @KtorDescription(operationId = "listNodes", summary = "List nodes")
            get("") {
                responds<ErrorResponse>(HttpStatusCode.Forbidden)
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_NODES))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(nodeService.listNodes())
            }

            @KtorDescription(operationId = "getNode", summary = "Get node")
            get("/{id}") {
                responds<NodeResponse>(HttpStatusCode.OK)
                responds<ErrorResponse>(HttpStatusCode.BadRequest)
                responds<ErrorResponse>(HttpStatusCode.Forbidden)
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_NODES))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val id = parseNodeId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid node ID"))
                call.respond(nodeService.getNode(id))
            }

            @KtorDescription(operationId = "trustNode", summary = "Trust node")
            post("/{id}/trust") {
                respondsNothing(HttpStatusCode.NoContent)
                responds<ErrorResponse>(HttpStatusCode.BadRequest)
                responds<ErrorResponse>(HttpStatusCode.Forbidden)
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_NODES))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val id = parseNodeId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid node ID"))
                nodeService.trustNode(id)
                call.respond(HttpStatusCode.NoContent)
            }

            @KtorDescription(operationId = "rejectNode", summary = "Reject node")
            post("/{id}/reject") {
                respondsNothing(HttpStatusCode.NoContent)
                responds<ErrorResponse>(HttpStatusCode.BadRequest)
                responds<ErrorResponse>(HttpStatusCode.Forbidden)
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_NODES))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val id = parseNodeId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid node ID"))
                nodeService.rejectNode(id)
                call.respond(HttpStatusCode.NoContent)
            }

            @KtorDescription(operationId = "rotateNodeToken", summary = "Rotate node token")
            post("/{id}/token/rotate") {
                responds<NodeKeyResponse>(HttpStatusCode.OK)
                responds<ErrorResponse>(HttpStatusCode.BadRequest)
                responds<ErrorResponse>(HttpStatusCode.Forbidden)
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_NODES))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val id = parseNodeId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid node ID"))
                call.respond(NodeKeyResponse(nodeService.rotateToken(id)))
            }

            @KtorDescription(operationId = "shutdownNode", summary = "Shutdown node agent")
            post("/{id}/shutdown") {
                responds<MessageResponse>(HttpStatusCode.Accepted)
                responds<ErrorResponse>(HttpStatusCode.BadRequest)
                responds<ErrorResponse>(HttpStatusCode.Forbidden)
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_NODES))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val id = parseNodeId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid node ID"))
                nodeService.shutdownNode(id)
                call.respond(HttpStatusCode.Accepted, MessageResponse("Shutdown command sent"))
            }

            @KtorDescription(operationId = "updateNode", summary = "Update node")
            patch("/{id}") {
                respondsNothing(HttpStatusCode.NoContent)
                responds<ErrorResponse>(HttpStatusCode.BadRequest)
                responds<ErrorResponse>(HttpStatusCode.Forbidden)
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_NODES))
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val id = parseNodeId(call.parameters["id"])
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid node ID"))
                val req = call.receive<PatchNodeRequest>()
                nodeService.updateNode(id, req)
                call.respond(HttpStatusCode.NoContent)
            }

            @KtorDescription(operationId = "decommissionNode", summary = "Decommission node")
            delete("/{id}") {
                respondsNothing(HttpStatusCode.NoContent)
                responds<ErrorResponse>(HttpStatusCode.BadRequest)
                responds<ErrorResponse>(HttpStatusCode.Forbidden)
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_NODES))
                    return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val id = parseNodeId(call.parameters["id"])
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid node ID"))
                nodeService.decommissionNode(id)
                call.respond(HttpStatusCode.NoContent)
            }

            @KtorDescription(operationId = "getNodeMetrics", summary = "Get node metrics")
            get("/{id}/metrics") {
                responds<NodeMetricsResponse>(HttpStatusCode.OK)
                responds<ErrorResponse>(HttpStatusCode.BadRequest)
                responds<ErrorResponse>(HttpStatusCode.Forbidden)
                val userId = call.userId()
                if (!PermissionResolver.hasPermission(userId, Permission.SYSTEM_NODES))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val id = parseNodeId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid node ID"))
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                    ?.coerceIn(1, 360) ?: 60
                call.respond(nodeService.getNodeMetrics(id, limit))
            }
        }
    }
}

private fun parseNodeId(raw: String?): kotlin.uuid.Uuid? =
    raw?.let {
        runCatching {
            UUID.fromString(it)
                .toKotlinUuid()
        }.getOrNull()
    }
