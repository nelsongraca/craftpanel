package io.craftpanel.master.routes.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerExportData(
    val version: Int? = 1,
    @SerialName("exported_at") val exportedAt: String,
    val name: String,
    @SerialName("display_name") val displayName: String,
    val description: String? = null,
    @SerialName("server_type") val serverType: String,
    @SerialName("mc_version") val mcVersion: String,
    @SerialName("itzg_image_tag") val itzgImageTag: String,
    @SerialName("memory_mb") val memoryMb: Int,
    @SerialName("cpu_limit_millicores") val cpuLimitMillicores: Int,
    @SerialName("exposed_externally") val exposedExternally: Boolean? = false,
    @SerialName("custom_hostname") val customHostname: String? = null,
    @SerialName("config_mode") val configMode: String,
    @SerialName("stop_command") val stopCommand: String,
    @SerialName("custom_server_jar") val customServerJar: String? = null,
    @SerialName("container_listen_port") val containerListenPort: Int? = null,
    @SerialName("container_protocol") val containerProtocol: String? = "TCP",
    @SerialName("disable_healthcheck") val disableHealthcheck: Boolean? = false,
    @SerialName("force_redownload") val forceRedownload: Boolean? = false,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("proxy_motd") val proxyMotd: String? = null,
    @SerialName("proxy_max_players") val proxyMaxPlayers: Int? = null,
    @SerialName("proxy_forwarding_mode") val proxyForwardingMode: String? = null,
    @SerialName("proxy_protocol") val proxyProtocol: Boolean? = false,
    @SerialName("forwarding_secret_enc") val forwardingSecretEnc: String? = null,
    @SerialName("forwarding_patch_file") val forwardingPatchFile: String? = null,
    @SerialName("backup_schedule") val backupSchedule: String? = null,
    @SerialName("backup_max_count") val backupMaxCount: Int? = 10,
    @SerialName("env_vars") val envVars: List<EnvVarExportItem>? = null,
    @SerialName("extra_ports") val extraPorts: List<ExtraPortExportItem>? = null,
    @SerialName("mods") val mods: List<ModExportItem>? = null,
    @SerialName("proxy_backends") val proxyBackends: List<ProxyBackendExportItem>? = null
)

@Serializable
data class EnvVarExportItem(val key: String, val value: String)

@Serializable
data class ExtraPortExportItem(val name: String, @SerialName("container_port") val containerPort: Int, val protocol: String = "TCP")

@Serializable
data class ModExportItem(
    @SerialName("modrinth_project_id") val modrinthProjectId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("pin_strategy") val pinStrategy: String,
    @SerialName("pinned_version_id") val pinnedVersionId: String? = null,
    val enabled: Boolean = true
)

@Serializable
data class ProxyBackendExportItem(
    @SerialName("backend_server_id") val backendServerId: String? = null,
    @SerialName("backend_server_name") val backendServerName: String? = null,
    @SerialName("backend_name") val backendName: String,
    val order: Int
)

@Serializable
data class NetworkExportData(
    val version: Int? = 1,
    @SerialName("exported_at") val exportedAt: String,
    val name: String,
    val description: String? = null,
    @SerialName("proxy_port") val proxyPort: Int? = null,
    val servers: List<ServerExportData>? = null
)

@Serializable
data class ImportServerRequest(val data: ServerExportData, @SerialName("node_id") val nodeId: String, @SerialName("network_id") val networkId: String? = null)

@Serializable
data class ImportNetworkRequest(val data: NetworkExportData, @SerialName("node_assignments") val nodeAssignments: Map<String, String>)
