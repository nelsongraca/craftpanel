package io.craftpanel.master.routes

import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.Permission
import io.craftpanel.master.auth.requireServerPermission
import io.craftpanel.master.service.CreateModRequest
import io.craftpanel.master.service.ModResponse
import io.craftpanel.master.service.ModService
import io.craftpanel.master.service.PatchModRequest
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

@Serializable
data class ModsListResponse(val mods: List<ModResponse>)

fun Route.modsRoutes(modService: ModService) = with(ModsRoutes(modService)) { register() }

class ModsRoutes(val modService: ModService) {

    private val log = LoggerFactory.getLogger(ModsRoutes::class.java)

    fun Route.register() {
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
                    val auth = call.requireServerPermission(Permission.SERVER_MODS)
                    call.respond(ModsListResponse(modService.listMods(auth.serverId)))
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
                    val auth = call.requireServerPermission(Permission.SERVER_MODS)
                    val req = call.receive<CreateModRequest>()
                    call.respond(HttpStatusCode.Created, modService.addMod(auth.serverId, req))
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
                    val auth = call.requireServerPermission(Permission.SERVER_MODS)
                    val modId = call.parameters["modId"]?.let {
                        runCatching {
                            Uuid.parse(it)

                        }.getOrNull()
                    }
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid mod ID"))
                    val req = call.receive<PatchModRequest>()
                    call.respond(modService.updateMod(auth.serverId, modId, req))
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
                    val auth = call.requireServerPermission(Permission.SERVER_MODS)
                    val modId = call.parameters["modId"]?.let {
                        runCatching {
                            Uuid.parse(it)

                        }.getOrNull()
                    }
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid mod ID"))
                    modService.deleteMod(auth.serverId, modId)
                    call.respond(HttpStatusCode.NoContent)
                }

                get("/search", {
                    operationId = "searchMods"
                    summary = "Search Modrinth mods"
                    request { pathParameter<String>("id"); queryParameter<String>("query"); queryParameter<Int>("limit"); queryParameter<String>("serverType"); queryParameter<String>("mcVersion") }
                    response {
                        code(HttpStatusCode.OK) { }
                        code(HttpStatusCode.BadGateway) { body<ErrorResponse>() }
                        code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                        code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                        code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                    }
                }) {
                    call.requireServerPermission(Permission.SERVER_MODS)
                    val query = call.request.queryParameters["query"]?.takeIf { it.isNotBlank() } ?: ""
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                        ?.coerceIn(1, 20) ?: 10
                    val serverType = call.request.queryParameters["serverType"] ?: ""
                    val mcVersion = call.request.queryParameters["mcVersion"] ?: ""
                    val result = modService.searchModrinth(query, limit, serverType, mcVersion)
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
}


