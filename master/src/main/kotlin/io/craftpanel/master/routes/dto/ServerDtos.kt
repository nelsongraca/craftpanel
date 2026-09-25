package io.craftpanel.master.routes.dto

import io.craftpanel.master.domain.ConfigMode
import io.craftpanel.master.domain.DesiredStatus
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.domain.synthesizeStatus
import io.craftpanel.master.service.ServerHostnames
import io.craftpanel.master.service.repo.ServerView
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
    @SerialName("cpu_limit_millicores") val cpuLimitMillicores: Int,
    @SerialName("exposed_externally") val exposedExternally: Boolean,
    @SerialName("public_subdomain") val publicSubdomain: String?,
    @SerialName("custom_hostname") val customHostname: String?,
    @SerialName("canonical_hostname") val canonicalHostname: String?,
    @SerialName("is_migrating") val isMigrating: Boolean,
    @SerialName("restart_pending") val restartPending: Boolean = false,
    val disabled: Boolean,
    @SerialName("config_mode") val configMode: ConfigMode,
    @SerialName("stop_command") val stopCommand: String,
    @SerialName("expires_at") val expiresAt: String?,
    @SerialName("last_player_count") val lastPlayerCount: Int?,
    @SerialName("last_player_names") val lastPlayerNames: List<String>?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("custom_server_jar") val customServerJar: String? = null,
    @SerialName("container_listen_port") val containerListenPort: Int? = null,
    @SerialName("container_protocol") val containerProtocol: String = "TCP",
    @SerialName("disable_healthcheck") val disableHealthcheck: Boolean = false,
    @SerialName("force_redownload") val forceRedownload: Boolean = false,
    @SerialName("jvm_metrics_enabled") val jvmMetricsEnabled: Boolean = true,
    // Admin override for the data directory name; null = server id.
    @SerialName("data_dir_name") val dataDirName: String? = null
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
    @SerialName("cpu_limit_millicores") val cpuLimitMillicores: Int = 0,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("custom_server_jar") val customServerJar: String? = null,
    @SerialName("container_listen_port") val containerListenPort: Int? = null,
    @SerialName("container_protocol") val containerProtocol: String? = null,
    @SerialName("disable_healthcheck") val disableHealthcheck: Boolean? = null,
    @SerialName("force_redownload") val forceRedownload: Boolean? = null,
    @SerialName("jvm_metrics_enabled") val jvmMetricsEnabled: Boolean? = null
)

@Serializable
data class CloneServerRequest(val name: String, @SerialName("display_name") val displayName: String? = null, val description: String? = null)

@Serializable
data class UpdateServerRequest(
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
    @SerialName("network_id") val networkId: String? = null,
    @SerialName("mc_version") val mcVersion: String? = null,
    @SerialName("itzg_image_tag") val itzgImageTag: String? = null,
    @SerialName("custom_server_jar") val customServerJar: String? = null,
    @SerialName("container_listen_port") val containerListenPort: Int? = null,
    @SerialName("container_protocol") val containerProtocol: String? = null,
    @SerialName("disable_healthcheck") val disableHealthcheck: Boolean? = null,
    @SerialName("force_redownload") val forceRedownload: Boolean? = null,
    @SerialName("jvm_metrics_enabled") val jvmMetricsEnabled: Boolean? = null
)

@Serializable
data class PatchResourcesRequest(
    @SerialName("memory_mb") val memoryMb: Int,
    @SerialName("cpu_limit_millicores") val cpuLimitMillicores: Int,
    @SerialName("itzg_image_tag") val itzgImageTag: String? = null
)

@Serializable
data class PatchExpirationRequest(@SerialName("expires_at") val expiresAt: String?)

@Serializable
data class PatchDisabledRequest(@SerialName("disabled") val disabled: Boolean)

@Serializable
data class UpdateServerDataDirRequest(
    // Null or empty clears the override (revert to the server id).
    @SerialName("data_dir_name") val dataDirName: String? = null
)

@Serializable
data class PatchExposureRequest(
    @SerialName("exposed_externally") val exposedExternally: Boolean,
    @SerialName("public_subdomain") val publicSubdomain: String? = null,
    @SerialName("custom_hostname") val customHostname: String? = null
)

internal fun ServerView.toResponse(serverHostnames: ServerHostnames, isMigrating: Boolean): ServerResponse {
    val canonicalHostname = serverHostnames.canonicalHostname(this)
    return ServerResponse(
        id = id.toString(),
        name = name,
        displayName = displayName,
        description = description,
        serverType = serverType.toDb(),
        mcVersion = mcVersion,
        itzgImageTag = itzgImageTag,
        status = synthesizeStatus(
            desired = DesiredStatus.fromDb(desiredStatus),
            reported = ServerStatus.fromDb(status)
        ),
        nodeId = nodeId.toString(),
        networkId = networkId?.toString(),
        hostPort = hostPort,
        memoryMb = memoryMb,
        cpuLimitMillicores = cpuLimitMillicores,
        exposedExternally = exposedExternally,
        publicSubdomain = publicSubdomain,
        customHostname = customHostname,
        canonicalHostname = canonicalHostname,
        isMigrating = isMigrating,
        restartPending = restartPending,
        disabled = disabled,
        configMode = ConfigMode.fromDb(configMode),
        stopCommand = stopCommand,
        expiresAt = expiresAt,
        lastPlayerCount = lastPlayerCount,
        lastPlayerNames = lastPlayerNames?.split(",")
            ?.filter { it.isNotBlank() },
        createdAt = createdAt,
        updatedAt = updatedAt,
        customServerJar = customServerJar,
        containerListenPort = containerListenPort,
        containerProtocol = containerProtocol,
        disableHealthcheck = disableHealthcheck,
        forceRedownload = forceRedownload,
        jvmMetricsEnabled = jvmMetricsEnabled,
        dataDirName = dataDirName
    )
}
