package io.craftpanel.master.routes

import io.craftpanel.master.auth.*
import io.craftpanel.master.routes.dto.*
import io.craftpanel.master.service.*
import io.github.smiley4.ktoropenapi.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.uuid.Uuid

fun Route.networksRoutes(networkService: NetworkService, exportService: ExportService) {
    authenticate(JWT_AUTH) {
        route("/api/networks") {
            get("", {
                operationId = "listNetworks"
                summary = "List networks"
                response {
                    code(HttpStatusCode.OK) { body<List<NetworkResponse>>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                call.respond(networkService.listNetworks(userId))
            }

            post("", {
                operationId = "createNetwork"
                summary = "Create network"
                request { body<CreateNetworkRequest>() }
                response {
                    code(HttpStatusCode.Created) { body<NetworkResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                call.requirePermission(Permission.NETWORK_CREATE)
                val req = call.receive<CreateNetworkRequest>()
                call.respond(HttpStatusCode.Created, networkService.createNetwork(req))
            }

            get("/{id}", {
                operationId = "getNetwork"
                summary = "Get network"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { body<NetworkDetailResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val id = parseNetworkId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid network ID"))
                if (!PermissionResolver.hasPermission(call.userId(), Permission.NETWORK_VIEW, networkId = id)) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }
                call.respond(networkService.getNetwork(id))
            }

            patch("/{id}", {
                operationId = "updateNetwork"
                summary = "Update network"
                request {
                    pathParameter<String>("id")
                    body<PatchNetworkRequest>()
                }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseNetworkId(call.parameters["id"])
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid network ID"))
                val networkId = id
                if (!PermissionResolver.hasPermission(userId, Permission.NETWORK_CONFIGURE, networkId = networkId)) {
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }
                val req = call.receive<PatchNetworkRequest>()
                networkService.updateNetwork(id, req)
                call.respond(HttpStatusCode.NoContent)
            }

            delete("/{id}", {
                operationId = "deleteNetwork"
                summary = "Delete network"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseNetworkId(call.parameters["id"])
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid network ID"))
                val networkId = id
                if (!PermissionResolver.hasPermission(userId, Permission.NETWORK_DELETE, networkId = networkId)) {
                    return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }
                networkService.deleteNetwork(id)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/{id}/export", {
                operationId = "exportNetwork"
                summary = "Export network and all member servers as JSON"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { body<NetworkExportData>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val id = parseNetworkId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid network ID"))
                if (!PermissionResolver.hasPermission(call.userId(), Permission.NETWORK_VIEW, networkId = id)) {
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                }
                val export = exportService.exportNetwork(id)
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${export.name}.craftpanel.json\"")
                call.respond(export)
            }

            post("/import", {
                operationId = "importNetwork"
                summary = "Import a network and its member servers from an exported JSON configuration"
                request { body<ImportNetworkRequest>() }
                response {
                    code(HttpStatusCode.Created) { body<NetworkResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                call.requirePermission(Permission.NETWORK_CREATE)
                val req = call.receive<ImportNetworkRequest>()
                val response = exportService.importNetwork(req.data, req.nodeAssignments)
                call.respond(HttpStatusCode.Created, response)
            }
        }
    }
}

private fun parseNetworkId(raw: String?): Uuid? = raw?.let {
    runCatching {
        Uuid.parse(it)
    }.getOrNull()
}
