package io.craftpanel.master.routes.dto

import io.craftpanel.master.domain.ConfigMode
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.ServerExposure
import io.craftpanel.master.service.repo.ServerRow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerResponse(
    val id: String,
    val name: String,
    @SerialName("display_name") val displayName: String,
    val description: String?,
    @SerialName("server_type") val serverType: String,
    @SerialName("mc_version") val mcVersion: String,
    @SerialName("itzg_image_tag") val itzgImageTag: String,
    val status: ServerStatus,
    @SerialName("node_id") val nodeId: String,
    @SerialName("network_id") val networkId: String?,
    @SerialName("host_port") val hostPort: Int,
    @SerialName("memory_mb") val memoryMb: Int,
    @SerialName("cpu_shares") val cpuShares: Int,
    @SerialName("exposed_externally") val exposedExternally: Boolean,
    @SerialName("public_subdomain") val publicSubdomain: String?,
    @SerialName("custom_hostname") val customHostname: String?,
    @SerialName("canonical_hostname") val canonicalHostname: String?,
    @SerialName("is_migrating") val isMigrating: Boolean,
    @SerialName("needs_recreate") val needsRecreate: Boolean,
    @SerialName("config_mode") val configMode: ConfigMode,
    @SerialName("stop_command") val stopCommand: String,
    @SerialName("last_player_count") val lastPlayerCount: Int?,
    @SerialName("last_player_names") val lastPlayerNames: List<String>?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class CreateServerRequest(
    val name: String,
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
    @SerialName("node_id") val nodeId: String,
    @SerialName("network_id") val networkId: String? = null,
    @SerialName("server_type") val serverType: String,
    @SerialName("mc_version") val mcVersion: String = "LATEST",
    @SerialName("itzg_image_tag") val itzgImageTag: String = "latest",
    @SerialName("memory_mb") val memoryMb: Int,
    @SerialName("cpu_shares") val cpuShares: Int = 0
)

@Serializable
data class CloneServerRequest(val name: String, @SerialName("display_name") val displayName: String? = null, val description: String? = null)

@Serializable
data class UpdateServerRequest(
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
    @SerialName("network_id") val networkId: String? = null,
    @SerialName("mc_version") val mcVersion: String? = null,
    @SerialName("itzg_image_tag") val itzgImageTag: String? = null
)

@Serializable
data class PatchResourcesRequest(@SerialName("memory_mb") val memoryMb: Int, @SerialName("cpu_shares") val cpuShares: Int, @SerialName("itzg_image_tag") val itzgImageTag: String? = null)

@Serializable
data class PatchExposureRequest(
    @SerialName("exposed_externally") val exposedExternally: Boolean,
    @SerialName("public_subdomain") val publicSubdomain: String? = null,
    @SerialName("custom_hostname") val customHostname: String? = null
)

internal fun ServerRow.toResponse(serverExposure: ServerExposure, isMigrating: Boolean): ServerResponse {
    val canonicalHostname = serverExposure.canonicalHostname(this)
    return ServerResponse(
        id = id.toString(),
        name = name,
        displayName = displayName,
        description = description,
        serverType = serverType.toDb(),
        mcVersion = mcVersion,
        itzgImageTag = itzgImageTag,
        status = ServerStatus.fromDb(status),
        nodeId = nodeId.toString(),
        networkId = networkId?.toString(),
        hostPort = hostPort,
        memoryMb = memoryMb,
        cpuShares = cpuShares,
        exposedExternally = exposedExternally,
        publicSubdomain = publicSubdomain,
        customHostname = customHostname,
        canonicalHostname = canonicalHostname,
        isMigrating = isMigrating,
        needsRecreate = needsRecreate,
        configMode = ConfigMode.fromDb(configMode),
        stopCommand = stopCommand,
        lastPlayerCount = lastPlayerCount,
        lastPlayerNames = lastPlayerNames?.split(",")
            ?.filter { it.isNotBlank() },
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
