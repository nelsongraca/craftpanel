package io.craftpanel.master.service

import io.craftpanel.master.domain.ModPinStrategy
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.*
import kotlin.uuid.Uuid

private val log = LoggerFactory.getLogger(ModService::class.java)

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

class ModService(private val serverRepository: ServerRepository, private val modRepository: ModRepository) {

    fun listMods(serverId: Uuid): List<ModResponse> = modRepository.listMods(serverId)
        .map { it.toResponse() }

    fun addMod(serverId: Uuid, req: CreateModRequest): ModResponse {
        if (req.pinStrategy == ModPinStrategy.PINNED && req.pinnedVersionId.isNullOrEmpty()) {
            throw UnprocessableException("pinned_version_id is required when pin_strategy is PINNED")
        }
        if (modRepository.findModByProjectId(serverId, req.modrinthProjectId) != null) {
            throw ConflictException("Mod already added to this server")
        }
        val mod = modRepository.createMod(
            serverId = serverId,
            modrinthProjectId = req.modrinthProjectId,
            displayName = req.displayName,
            pinStrategy = req.pinStrategy.name,
            pinnedVersionId = req.pinnedVersionId,
            installedVersionId = null
        )
        serverRepository.updateNeedsRecreate(serverId, true)
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
        serverRepository.updateNeedsRecreate(serverId, true)
        return modRepository.findModById(modId)!!
            .toResponse()
    }

    fun deleteMod(serverId: Uuid, modId: Uuid) {
        modRepository.findModById(modId)
            ?.takeIf { it.serverId == serverId }
            ?: throw NotFoundException("Mod not found")
        modRepository.deleteMod(modId)
        serverRepository.updateNeedsRecreate(serverId, true)
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
            val httpClient = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "CraftPanel/1.0")
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            ModrinthSearchResult(response.statusCode(), response.body())
        } catch (e: Exception) {
            log.error("Modrinth search failed for query='$query'", e)
            ModrinthSearchResult(502, "")
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
