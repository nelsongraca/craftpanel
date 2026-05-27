package io.craftpanel.master.routes

import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.database.schema.ServerMods
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.util.toKotlinUuid
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

private val VALID_PIN_STRATEGIES = setOf("PINNED", "LATEST", "BETA", "ALPHA")

@Serializable
data class ModResponse(
    val id: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("modrinth_project_id") val modrinthProjectId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("pin_strategy") val pinStrategy: String,
    @SerialName("pinned_version_id") val pinnedVersionId: String?,
    @SerialName("installed_version_id") val installedVersionId: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class CreateModRequest(
    @SerialName("modrinth_project_id") val modrinthProjectId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("pin_strategy") val pinStrategy: String,
    @SerialName("pinned_version_id") val pinnedVersionId: String? = null,
)

@Serializable
data class PatchModRequest(
    @SerialName("pin_strategy") val pinStrategy: String? = null,
    @SerialName("pinned_version_id") val pinnedVersionId: String? = null,
)

fun Route.modsRoutes() {
    authenticate("auth-jwt") {
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
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                val id = parseModServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@get
                }
                val serverRow = transaction { Servers.selectAll().where { Servers.id eq id }.firstOrNull() } ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@get
                }
                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = serverRow[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.mods", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@get
                }

                val mods = transaction {
                    ServerMods.selectAll()
                        .where { ServerMods.serverId eq id }
                        .map { it.toModResponse() }
                }
                call.respond(mapOf("mods" to mods))
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
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                val id = parseModServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@post
                }
                val serverRow = transaction { Servers.selectAll().where { Servers.id eq id }.firstOrNull() } ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@post
                }
                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = serverRow[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.mods", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@post
                }

                val req = call.receive<CreateModRequest>()

                if (req.pinStrategy !in VALID_PIN_STRATEGIES) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("pin_strategy must be one of: ${VALID_PIN_STRATEGIES.joinToString()}"))
                    return@post
                }
                if (req.pinStrategy == "PINNED" && req.pinnedVersionId.isNullOrEmpty()) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("pinned_version_id is required when pin_strategy is PINNED"))
                    return@post
                }

                val result = transaction {
                    val alreadyExists = ServerMods.selectAll()
                        .where { (ServerMods.serverId eq id) and (ServerMods.modrinthProjectId eq req.modrinthProjectId) }
                        .firstOrNull() != null
                    if (alreadyExists) return@transaction null

                    val modId = ServerMods.insert {
                        it[ServerMods.serverId] = id
                        it[ServerMods.modrinthProjectId] = req.modrinthProjectId
                        it[ServerMods.displayName] = req.displayName
                        it[ServerMods.pinStrategy] = req.pinStrategy
                        it[ServerMods.pinnedVersionId] = req.pinnedVersionId
                    }[ServerMods.id]

                    ServerMods.selectAll().where { ServerMods.id eq modId }.first().toModResponse()
                }

                if (result == null) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Mod already added to this server"))
                    return@post
                }
                call.respond(HttpStatusCode.Created, result)
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
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                val id = parseModServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@patch
                }
                val modKotlinId = call.parameters["modId"]
                    ?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid mod ID"))
                        return@patch
                    }

                val serverRow = transaction { Servers.selectAll().where { Servers.id eq id }.firstOrNull() } ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@patch
                }
                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = serverRow[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.mods", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@patch
                }

                transaction { ServerMods.selectAll().where { (ServerMods.id eq modKotlinId) and (ServerMods.serverId eq id) }.firstOrNull() }
                    ?: run {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Mod not found"))
                        return@patch
                    }

                val req = call.receive<PatchModRequest>()

                if (req.pinStrategy != null && req.pinStrategy !in VALID_PIN_STRATEGIES) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("pin_strategy must be one of: ${VALID_PIN_STRATEGIES.joinToString()}"))
                    return@patch
                }
                if (req.pinStrategy == "PINNED" && req.pinnedVersionId.isNullOrEmpty()) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("pinned_version_id is required when pin_strategy is PINNED"))
                    return@patch
                }

                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                val updated = transaction {
                    ServerMods.update({ (ServerMods.id eq modKotlinId) and (ServerMods.serverId eq id) }) {
                        if (req.pinStrategy != null) it[ServerMods.pinStrategy] = req.pinStrategy
                        if (req.pinnedVersionId != null) it[ServerMods.pinnedVersionId] = req.pinnedVersionId
                        it[ServerMods.updatedAt] = now
                    }
                    ServerMods.selectAll().where { ServerMods.id eq modKotlinId }.first().toModResponse()
                }
                call.respond(HttpStatusCode.OK, updated)
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
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                val id = parseModServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@delete
                }
                val modKotlinId = call.parameters["modId"]
                    ?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid mod ID"))
                        return@delete
                    }

                val serverRow = transaction { Servers.selectAll().where { Servers.id eq id }.firstOrNull() } ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@delete
                }
                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = serverRow[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.mods", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@delete
                }

                val deleted = transaction {
                    ServerMods.deleteWhere { (ServerMods.id eq modKotlinId) and (ServerMods.serverId eq id) }
                }
                if (deleted == 0) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Mod not found"))
                    return@delete
                }
                call.respond(HttpStatusCode.NoContent)
            }

            get("/search", {
                operationId = "searchMods"
                summary = "Search Modrinth mods"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                val id = parseModServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@get
                }
                val serverRow = transaction { Servers.selectAll().where { Servers.id eq id }.firstOrNull() } ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@get
                }
                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = serverRow[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.mods", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@get
                }

                val query = call.request.queryParameters["query"]?.takeIf { it.isNotBlank() } ?: ""
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 20) ?: 10

                val url = buildString {
                    append("https://api.modrinth.com/v2/search?query=")
                    append(java.net.URLEncoder.encode(query, "UTF-8"))
                    append("&facets=[[%22project_type:mod%22]]&limit=")
                    append(limit)
                }

                val httpClient = HttpClient.newHttpClient()
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "CraftPanel/1.0")
                    .GET()
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

                call.response.headers.append("Content-Type", "application/json")
                call.respond(HttpStatusCode.fromValue(response.statusCode()), response.body())
            }
        }
    }
}

private fun parseModServerId(raw: String?): kotlin.uuid.Uuid? =
    raw?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }

private fun org.jetbrains.exposed.v1.core.ResultRow.toModResponse() = ModResponse(
    id = this[ServerMods.id].toString(),
    serverId = this[ServerMods.serverId].toString(),
    modrinthProjectId = this[ServerMods.modrinthProjectId],
    displayName = this[ServerMods.displayName],
    pinStrategy = this[ServerMods.pinStrategy],
    pinnedVersionId = this[ServerMods.pinnedVersionId],
    installedVersionId = this[ServerMods.installedVersionId],
    createdAt = this[ServerMods.createdAt].toString(),
    updatedAt = this[ServerMods.updatedAt].toString(),
)

fun modrinthProjectsEnvVar(mods: List<org.jetbrains.exposed.v1.core.ResultRow>): String =
    mods.joinToString(",") { row ->
        val projectId = row[ServerMods.modrinthProjectId]
        when (row[ServerMods.pinStrategy]) {
            "PINNED" -> "${projectId}:${row[ServerMods.pinnedVersionId]}"
            "BETA"   -> "$projectId:beta"
            "ALPHA"  -> "$projectId:alpha"
            else     -> projectId   // LATEST
        }
    }
