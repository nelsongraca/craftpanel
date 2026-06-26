package io.craftpanel.master.service

import io.craftpanel.master.database.schema.ServerMods
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.ModPinStrategy
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Clock
import kotlin.uuid.Uuid
import io.craftpanel.master.util.toUtcString

@Serializable
data class ModResponse(
    val id: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("modrinth_project_id") val modrinthProjectId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("pin_strategy") val pinStrategy: ModPinStrategy,
    @SerialName("pinned_version_id") val pinnedVersionId: String?,
    @SerialName("installed_version_id") val installedVersionId: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class CreateModRequest(
    @SerialName("modrinth_project_id") val modrinthProjectId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("pin_strategy") val pinStrategy: ModPinStrategy,
    @SerialName("pinned_version_id") val pinnedVersionId: String? = null,
)

@Serializable
data class PatchModRequest(
    @SerialName("pin_strategy") val pinStrategy: ModPinStrategy? = null,
    @SerialName("pinned_version_id") val pinnedVersionId: String? = null,
)

data class ModrinthSearchResult(val statusCode: Int, val body: String)

class ModService {

    fun listMods(serverId: Uuid): List<ModResponse> =
        transaction {
            ServerMods.selectAll()
                .where { ServerMods.serverId eq serverId }
                .map { it.toModResponse() }
        }

    fun addMod(serverId: Uuid, req: CreateModRequest): ModResponse {
        if (req.pinStrategy == ModPinStrategy.PINNED && req.pinnedVersionId.isNullOrEmpty())
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
                it[ServerMods.pinStrategy] = req.pinStrategy.name
                it[ServerMods.pinnedVersionId] = req.pinnedVersionId
            }[ServerMods.id]
            markNeedsRecreate(serverId)
            ServerMods.selectAll()
                .where { ServerMods.id eq modId }
                .first()
                .toModResponse()
        }
    }

    fun updateMod(serverId: Uuid, modId: Uuid, req: PatchModRequest): ModResponse {
        transaction {
            ServerMods.selectAll()
                .where { (ServerMods.id eq modId) and (ServerMods.serverId eq serverId) }
                .firstOrNull()
        }
            ?: throw NotFoundException("Mod not found")
        if (req.pinStrategy == ModPinStrategy.PINNED && req.pinnedVersionId.isNullOrEmpty())
            throw UnprocessableException("pinned_version_id is required when pin_strategy is PINNED")
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)
        return transaction {
            ServerMods.update({ (ServerMods.id eq modId) and (ServerMods.serverId eq serverId) }) {
                if (req.pinStrategy != null) it[ServerMods.pinStrategy] = req.pinStrategy.name
                if (req.pinnedVersionId != null) {
                    it[ServerMods.pinnedVersionId] = req.pinnedVersionId
                }
                else if (req.pinStrategy != null && req.pinStrategy != ModPinStrategy.PINNED) {
                    it[ServerMods.pinnedVersionId] = null
                }
                it[ServerMods.updatedAt] = now
            }
            markNeedsRecreate(serverId)
            ServerMods.selectAll()
                .where { ServerMods.id eq modId }
                .first()
                .toModResponse()
        }
    }

    fun deleteMod(serverId: Uuid, modId: Uuid) {
        val deleted = transaction { ServerMods.deleteWhere { (ServerMods.id eq modId) and (ServerMods.serverId eq serverId) } }
        if (deleted == 0) throw NotFoundException("Mod not found")
        transaction { markNeedsRecreate(serverId) }
    }

    fun searchModrinth(query: String, limit: Int, serverType: String = "", mcVersion: String = ""): ModrinthSearchResult {
        val typeUpper = serverType.uppercase()
        val projectType = if (typeUpper in PLUGIN_SERVER_TYPES) "plugin" else "mod"
        val loader = LOADER_BY_SERVER_TYPE[typeUpper]
        val url = buildString {
            append("https://api.modrinth.com/v2/search?query=")
            append(URLEncoder.encode(query, "UTF-8"))
            val facets = buildList {
                add("[\"project_type:$projectType\"]")
                if (loader != null) add("[\"loaders:$loader\"]")
                if (mcVersion.isNotBlank() && mcVersion.uppercase() != "LATEST") add("[\"game_versions:$mcVersion\"]")
            }
            append("&facets=[")
            append(facets.joinToString(","))
            append("]&limit=")
            append(limit)
        }
        return try {
            val httpClient = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "CraftPanel/1.0")
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            ModrinthSearchResult(response.statusCode(), response.body())
        }
        catch (_: Exception) {
            ModrinthSearchResult(502, "")
        }
    }

    fun markNeedsRecreate(serverId: Uuid) {
        Servers.update({ Servers.id eq serverId }) { it[Servers.needsRecreate] = true }
    }

    fun buildModrinthEnvVar(serverId: Uuid): String =
        transaction {
            ServerMods.selectAll()
                .where { ServerMods.serverId eq serverId }
                .toList()
        }
            .joinToString(",") { row ->
                val projectId = row[ServerMods.modrinthProjectId]
                when (ModPinStrategy.fromDb(row[ServerMods.pinStrategy])) {
                    ModPinStrategy.PINNED -> "${projectId}:${row[ServerMods.pinnedVersionId]}"
                    ModPinStrategy.BETA   -> "$projectId:beta"
                    ModPinStrategy.ALPHA  -> "$projectId:alpha"
                    else                  -> projectId
                }
            }
}

private val PLUGIN_SERVER_TYPES = setOf(
    "PAPER", "PURPUR", "SPIGOT", "BUKKIT",
    "VELOCITY", "BUNGEECORD", "WATERFALL",
)

private val LOADER_BY_SERVER_TYPE = mapOf(
    "FABRIC" to "fabric", "FORGE" to "forge", "NEOFORGE" to "neoforge", "QUILT" to "quilt",
    "PAPER" to "paper", "PURPUR" to "purpur", "SPIGOT" to "spigot", "BUKKIT" to "bukkit",
    "VELOCITY" to "velocity", "BUNGEECORD" to "bungeecord", "WATERFALL" to "waterfall",
)

private fun org.jetbrains.exposed.v1.core.ResultRow.toModResponse() = ModResponse(
    id = this[ServerMods.id].toString(),
    serverId = this[ServerMods.serverId].toString(),
    modrinthProjectId = this[ServerMods.modrinthProjectId],
    displayName = this[ServerMods.displayName],
    pinStrategy = ModPinStrategy.fromDb(this[ServerMods.pinStrategy]),
    pinnedVersionId = this[ServerMods.pinnedVersionId],
    installedVersionId = this[ServerMods.installedVersionId],
    createdAt = this[ServerMods.createdAt].toUtcString(),
    updatedAt = this[ServerMods.updatedAt].toUtcString(),
)
