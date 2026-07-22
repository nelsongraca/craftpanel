package io.craftpanel.master.service

import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.ServerRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class ProxySettingsResponse(val motd: String?, @SerialName("max_players") val maxPlayers: Int?, @SerialName("forwarding_mode") val forwardingMode: String?)

@Serializable
data class UpdateProxySettingsRequest(val motd: String?, @SerialName("max_players") val maxPlayers: Int?, @SerialName("forwarding_mode") val forwardingMode: String?)

/**
 * Proxy-side settings (MOTD, max players, forwarding mode) stored on the proxy
 * server row. Persisting them marks the proxy for recreate and, if the proxy is
 * currently running, writes the refreshed patch immediately via [ProxyConfigPatchService].
 */
class ProxySettingsService(
    private val serverRepository: ServerRepository,
    private val proxyConfigPatchService: ProxyConfigPatchService,
    private val writeFile: suspend (Uuid, String, ByteArray) -> Unit
) {

    fun getSettings(proxyServerId: Uuid): ProxySettingsResponse {
        val row = serverRepository.findById(proxyServerId) ?: throw NotFoundException("Server not found")
        if (!row.serverType.isProxy) throw ConflictException("Server is not a proxy type")
        return ProxySettingsResponse(
            motd = row.proxyMotd,
            maxPlayers = row.proxyMaxPlayers,
            forwardingMode = row.proxyForwardingMode
        )
    }

    suspend fun updateSettings(proxyServerId: Uuid, req: UpdateProxySettingsRequest): ProxySettingsResponse {
        val row = serverRepository.findById(proxyServerId) ?: throw NotFoundException("Server not found")
        if (!row.serverType.isProxy) throw ConflictException("Server is not a proxy type")

        val mode = req.forwardingMode?.uppercase()
        validateForwardingMode(row.serverType, mode)
        if (req.maxPlayers != null && req.maxPlayers <= 0) {
            throw UnprocessableException("maxPlayers must be greater than 0")
        }

        serverRepository.updateProxySettings(proxyServerId, req.motd, req.maxPlayers, mode)
        serverRepository.updateNeedsRecreate(proxyServerId, true)
        writePatchIfRunning(proxyServerId, row.status)
        return getSettings(proxyServerId)
    }

    private suspend fun writePatchIfRunning(proxyServerId: Uuid, status: String) {
        if (ServerStatus.fromDb(status) != ServerStatus.HEALTHY) return
        val patch = proxyConfigPatchService.generatePatch(proxyServerId) ?: return
        writeFile(proxyServerId, "craftpanel-patch.json", patch.toByteArray())
    }

    private fun validateForwardingMode(serverType: ServerType, mode: String?) {
        if (mode == null) return
        val allowed = if (serverType == ServerType.VELOCITY) VELOCITY_FORWARDING_MODES else BUNGEE_FORWARDING_MODES
        if (mode !in allowed) {
            throw UnprocessableException("forwardingMode must be one of: ${allowed.joinToString()}")
        }
    }

    companion object {
        val VELOCITY_FORWARDING_MODES = setOf("NONE", "LEGACY", "MODERN", "BUNGEEGUARD")
        val BUNGEE_FORWARDING_MODES = setOf("LEGACY", "OFF")
    }
}
