package io.craftpanel.master.routes

import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.service.CreateModRequest
import io.craftpanel.master.service.ModResponse
import io.craftpanel.master.service.ModService
import io.craftpanel.master.service.PatchModRequest
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
import java.util.*

fun Route.modsRoutes(modService: ModService) {
    authenticate(JWT_AUTH) {
        route("/api/servers/{id}/mods") {

            get("", {
                operationId = "listMods"
                summary = "List server mods"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { body<Map<String, List<ModResponse>>>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseModServerId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = modService.getServerScope(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                val serverIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_MODS, serverId = serverIdJava, networkId = scope.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                call.respond(mapOf("mods" to modService.listMods(id)))
            }

            post("", {
                operationId = "addMod"
                summary = "Add mod to server"
                request { pathParameter<String>("id"); body<CreateModRequest>() }
                response {
                    code(HttpStatusCode.Created) { body<ModResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseModServerId(call.parameters["id"])
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = modService.getServerScope(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                val serverIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_MODS, serverId = serverIdJava, networkId = scope.networkId))
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<CreateModRequest>()
                call.respond(HttpStatusCode.Created, modService.addMod(id, req))
            }

            patch("/{modId}", {
                operationId = "updateMod"
                summary = "Update server mod"
                request { pathParameter<String>("id"); pathParameter<String>("modId"); body<PatchModRequest>() }
                response {
                    code(HttpStatusCode.OK) { body<ModResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseModServerId(call.parameters["id"])
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val modId = call.parameters["modId"]?.let {
                    runCatching {
                        UUID.fromString(it)
                            .toKotlinUuid()
                    }.getOrNull()
                }
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid mod ID"))
                val scope = modService.getServerScope(id)
                    ?: return@patch call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                val serverIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_MODS, serverId = serverIdJava, networkId = scope.networkId))
                    return@patch call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val req = call.receive<PatchModRequest>()
                call.respond(modService.updateMod(id, modId, req))
            }

            delete("/{modId}", {
                operationId = "deleteMod"
                summary = "Remove mod from server"
                request { pathParameter<String>("id"); pathParameter<String>("modId") }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseModServerId(call.parameters["id"])
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val modId = call.parameters["modId"]?.let {
                    runCatching {
                        UUID.fromString(it)
                            .toKotlinUuid()
                    }.getOrNull()
                }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid mod ID"))
                val scope = modService.getServerScope(id)
                    ?: return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                val serverIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_MODS, serverId = serverIdJava, networkId = scope.networkId))
                    return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                modService.deleteMod(id, modId)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/search", {
                operationId = "searchMods"
                summary = "Search Modrinth mods"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { }
                    code(HttpStatusCode.BadGateway) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val userId = call.userId()
                val id = parseModServerId(call.parameters["id"])
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                val scope = modService.getServerScope(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                val serverIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, Permission.SERVER_MODS, serverId = serverIdJava, networkId = scope.networkId))
                    return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                val query = call.request.queryParameters["query"]?.takeIf { it.isNotBlank() } ?: ""
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                    ?.coerceIn(1, 20) ?: 10
                val result = modService.searchModrinth(query, limit)
                if (result.statusCode !in 200..299) {
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse("Mod search upstream unavailable"))
                    return@get
                }
                call.response.headers.append("Content-Type", "application/json")
                call.respond(HttpStatusCode.OK, result.body)
            }
        }
    }
}

private fun parseModServerId(raw: String?): kotlin.uuid.Uuid? =
    raw?.let {
        runCatching {
            UUID.fromString(it)
                .toKotlinUuid()
        }.getOrNull()
    }
