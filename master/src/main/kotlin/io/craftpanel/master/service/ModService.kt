package io.craftpanel.master.service

import io.craftpanel.master.database.schema.ServerMods
import io.craftpanel.master.database.schema.Servers
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

data class ModrinthSearchResult(val statusCode: Int, val body: String)

class ModService {

    data class ServerScope(val networkId: UUID?)

    fun getServerScope(serverId: kotlin.uuid.Uuid): ServerScope? =
        transaction {
            Servers.selectAll().where { Servers.id eq serverId }.firstOrNull()
                ?.let { ServerScope(it[Servers.networkId]?.let { nid -> UUID.fromString(nid.toString()) }) }
        }

    fun listMods(serverId: kotlin.uuid.Uuid): List<ModResponse> =
        transaction { ServerMods.selectAll().where { ServerMods.serverId eq serverId }.map { it.toModResponse() } }

    fun addMod(serverId: kotlin.uuid.Uuid, req: CreateModRequest): ModResponse {
        if (req.pinStrategy !in VALID_PIN_STRATEGIES)
            throw UnprocessableException("pin_strategy must be one of: ${VALID_PIN_STRATEGIES.joinToString()}")
        if (req.pinStrategy == "PINNED" && req.pinnedVersionId.isNullOrEmpty())
            throw UnprocessableException("pinned_version_id is required when pin_strategy is PINNED")
        return transaction {
            val alreadyExists = ServerMods.selectAll()
                .where { (ServerMods.serverId eq serverId) and (ServerMods.modrinthProjectId eq req.modrinthProjectId) }
                .firstOrNull() != null
            if (alreadyExists) throw ConflictException("Mod already added to this server")
            val modId = ServerMods.insert {
                it[ServerMods.serverId] = serverId
                it[ServerMods.modrinthProjectId] = req.modrinthProjectId
                it[ServerMods.displayName] = req.displayName
                it[ServerMods.pinStrategy] = req.pinStrategy
                it[ServerMods.pinnedVersionId] = req.pinnedVersionId
            }[ServerMods.id]
            ServerMods.selectAll().where { ServerMods.id eq modId }.first().toModResponse()
        }
    }

    fun updateMod(serverId: kotlin.uuid.Uuid, modId: kotlin.uuid.Uuid, req: PatchModRequest): ModResponse {
        transaction { ServerMods.selectAll().where { (ServerMods.id eq modId) and (ServerMods.serverId eq serverId) }.firstOrNull() }
            ?: throw NotFoundException("Mod not found")
        if (req.pinStrategy != null && req.pinStrategy !in VALID_PIN_STRATEGIES)
            throw UnprocessableException("pin_strategy must be one of: ${VALID_PIN_STRATEGIES.joinToString()}")
        if (req.pinStrategy == "PINNED" && req.pinnedVersionId.isNullOrEmpty())
            throw UnprocessableException("pinned_version_id is required when pin_strategy is PINNED")
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        return transaction {
            ServerMods.update({ (ServerMods.id eq modId) and (ServerMods.serverId eq serverId) }) {
                if (req.pinStrategy != null) it[ServerMods.pinStrategy] = req.pinStrategy
                if (req.pinnedVersionId != null) it[ServerMods.pinnedVersionId] = req.pinnedVersionId
                it[ServerMods.updatedAt] = now
            }
            ServerMods.selectAll().where { ServerMods.id eq modId }.first().toModResponse()
        }
    }

    fun deleteMod(serverId: kotlin.uuid.Uuid, modId: kotlin.uuid.Uuid) {
        val deleted = transaction { ServerMods.deleteWhere { (ServerMods.id eq modId) and (ServerMods.serverId eq serverId) } }
        if (deleted == 0) throw NotFoundException("Mod not found")
    }

    fun searchModrinth(query: String, limit: Int): ModrinthSearchResult {
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
        return ModrinthSearchResult(response.statusCode(), response.body())
    }

    fun buildModrinthEnvVar(serverId: kotlin.uuid.Uuid): String =
        transaction { ServerMods.selectAll().where { ServerMods.serverId eq serverId }.toList() }
            .joinToString(",") { row ->
                val projectId = row[ServerMods.modrinthProjectId]
                when (row[ServerMods.pinStrategy]) {
                    "PINNED" -> "${projectId}:${row[ServerMods.pinnedVersionId]}"
                    "BETA" -> "$projectId:beta"
                    "ALPHA" -> "$projectId:alpha"
                    else -> projectId
                }
            }
}

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
