package io.craftpanel.master.service

import io.craftpanel.master.database.entity.ServerEntity
import io.craftpanel.master.domain.ModPinStrategy
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import kotlin.uuid.Uuid

private val log = LoggerFactory.getLogger(ModService::class.java)

// Kept in sync with MOD_SERVER_TYPES in frontend/app/(app)/servers/[id]/mods-tab.tsx
private val MOD_LOADER_TYPES = setOf(ServerType.FABRIC, ServerType.FORGE, ServerType.NEOFORGE, ServerType.QUILT)

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
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreateModRequest(
    @SerialName("modrinth_project_id") val modrinthProjectId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("pin_strategy") val pinStrategy: ModPinStrategy,
    @SerialName("pinned_version_id") val pinnedVersionId: String? = null
)

@Serializable
data class PatchModRequest(@SerialName("pin_strategy") val pinStrategy: ModPinStrategy? = null, @SerialName("pinned_version_id") val pinnedVersionId: String? = null)

data class ModrinthSearchResult(val statusCode: Int, val body: String)

@Serializable
private data class ModrinthVersion(val id: String)

class ModService(
    private val serverRepository: ServerRepository,
    private val modRepository: ModRepository,
    private val client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
) {

    fun listMods(serverId: Uuid): List<ModResponse> = modRepository.listMods(serverId)
        .map { it.toResponse() }

    fun addMod(serverId: Uuid, req: CreateModRequest): ModResponse {
        if (req.pinStrategy == ModPinStrategy.PINNED && req.pinnedVersionId.isNullOrEmpty()) {
            throw UnprocessableException("pinned_version_id is required when pin_strategy is PINNED")
        }
        if (modRepository.findModByProjectId(serverId, req.modrinthProjectId) != null) {
            throw ConflictException("Mod already added to this server")
        }
        val server = serverRepository.findById(serverId)
            ?: throw NotFoundException("Server not found")
        if (!hasCompatibleVersion(req.modrinthProjectId, server.serverType, server.mcVersion, req.pinnedVersionId)) {
            throw UnprocessableException(
                "No Modrinth version of '${req.modrinthProjectId}' is compatible with ${server.serverType} ${server.mcVersion}"
            )
        }
        val mod = modRepository.createMod(
            serverId = serverId,
            modrinthProjectId = req.modrinthProjectId,
            displayName = req.displayName,
            pinStrategy = req.pinStrategy.name,
            pinnedVersionId = req.pinnedVersionId,
            installedVersionId = null
        )
        transaction { ServerEntity.findById(serverId)?.let { it.needsRecreate = true } }
        return mod.toResponse()
    }

    fun updateMod(serverId: Uuid, modId: Uuid, req: PatchModRequest): ModResponse {
        modRepository.findModById(modId)
            ?.takeIf { it.serverId == serverId }
            ?: throw NotFoundException("Mod not found")
        if (req.pinStrategy == ModPinStrategy.PINNED && req.pinnedVersionId.isNullOrEmpty()) {
            throw UnprocessableException("pinned_version_id is required when pin_strategy is PINNED")
        }
        val pinnedVersionId = when {
            req.pinnedVersionId != null -> req.pinnedVersionId
            req.pinStrategy != null && req.pinStrategy != ModPinStrategy.PINNED -> null
            else -> modRepository.findModById(modId)?.pinnedVersionId
        }
        modRepository.updateMod(modId, req.pinStrategy?.name, pinnedVersionId, null)
        transaction { ServerEntity.findById(serverId)?.let { it.needsRecreate = true } }
        return modRepository.findModById(modId)!!
            .toResponse()
    }

    fun deleteMod(serverId: Uuid, modId: Uuid) {
        modRepository.findModById(modId)
            ?.takeIf { it.serverId == serverId }
            ?: throw NotFoundException("Mod not found")
        modRepository.deleteMod(modId)
        transaction { ServerEntity.findById(serverId)?.let { it.needsRecreate = true } }
    }

    fun searchModrinth(query: String, limit: Int, serverType: String = "", mcVersion: String = ""): ModrinthSearchResult {
        val type = runCatching { ServerType.valueOf(serverType.uppercase()) }.getOrNull()
        val projectType = if (type?.supportsPlugins == true) "plugin" else "mod"
        val loader = type?.let { ServerType.LOADER_BY_TYPE[it] }
        val url = buildString {
            append("https://api.modrinth.com/v2/search?query=")
            append(URLEncoder.encode(query, "UTF-8"))
            val facets = buildList {
                add("[\"project_type:$projectType\"]")
                if (loader != null) add("[\"categories:$loader\"]")
                if (mcVersion.isNotBlank() && mcVersion.uppercase() != "LATEST") add("[\"game_versions:$mcVersion\"]")
            }
            append("&facets=")
            append(URLEncoder.encode("[${facets.joinToString(",")}]", "UTF-8"))
            append("&limit=")
            append(limit)
        }
        return try {
            runBlocking {
                val response = client.get(url) {
                    header(HttpHeaders.UserAgent, "CraftPanel/1.0")
                }
                ModrinthSearchResult(response.status.value, response.bodyAsText())
            }
        } catch (e: Exception) {
            log.error("Modrinth search failed for query='$query'", e)
            ModrinthSearchResult(502, "")
        }
    }

    /**
     * Verifies at least one Modrinth version exists for [projectId] matching the server's loader + MC version.
     * Applies to every pin strategy (including PINNED, re-verified server-side rather than trusting the client) so
     * an incompatible mod can never be attached — itzg resolves "latest"/"beta"/"alpha" lazily at container
     * startup and simply fails to boot if nothing matches.
     */
    private fun hasCompatibleVersion(projectId: String, serverType: ServerType, mcVersion: String, pinnedVersionId: String?): Boolean {
        // Mirrors the frontend's fetchModrinthVersions: only actual mod loaders (Fabric/Forge/NeoForge/Quilt) get a
        // loaders filter — plugin/proxy servers (Paper, Velocity, ...) are filtered by game_version only, since a
        // Paper-compatible plugin may only be tagged "spigot"/"bukkit" on Modrinth rather than "paper".
        val loader = if (serverType in MOD_LOADER_TYPES) ServerType.LOADER_BY_TYPE[serverType] else null
        val url = buildString {
            append("https://api.modrinth.com/v2/project/")
            append(URLEncoder.encode(projectId, "UTF-8"))
            append("/version?")
            if (loader != null) {
                append("loaders=")
                append(URLEncoder.encode("[\"$loader\"]", "UTF-8"))
                append("&")
            }
            append("game_versions=")
            append(URLEncoder.encode("[\"$mcVersion\"]", "UTF-8"))
        }
        return try {
            runBlocking {
                val response = client.get(url) {
                    header(HttpHeaders.UserAgent, "CraftPanel/1.0")
                }
                if (!response.status.isSuccess()) return@runBlocking false
                val versions = response.body<List<ModrinthVersion>>()
                if (pinnedVersionId != null) versions.any { it.id == pinnedVersionId } else versions.isNotEmpty()
            }
        } catch (e: Exception) {
            log.error("Modrinth version check failed for project='$projectId'", e)
            throw BadGatewayException("Could not verify Modrinth compatibility for '$projectId'")
        }
    }

    fun buildModrinthEnvVar(serverId: Uuid): String = modRepository.listMods(serverId)
        .joinToString(",") { row ->
            val projectId = row.modrinthProjectId
            when (ModPinStrategy.fromDb(row.pinStrategy)) {
                ModPinStrategy.PINNED -> "$projectId:${row.pinnedVersionId}"
                ModPinStrategy.BETA -> "$projectId:beta"
                ModPinStrategy.ALPHA -> "$projectId:alpha"
                else -> projectId
            }
        }
}

private fun ModRow.toResponse() = ModResponse(
    id = id.toString(),
    serverId = serverId.toString(),
    modrinthProjectId = modrinthProjectId,
    displayName = displayName,
    pinStrategy = ModPinStrategy.fromDb(pinStrategy),
    pinnedVersionId = pinnedVersionId,
    installedVersionId = installedVersionId,
    createdAt = createdAt,
    updatedAt = updatedAt
)
