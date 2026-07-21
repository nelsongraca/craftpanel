package io.craftpanel.master.service

import io.craftpanel.master.service.repo.ServerRepository
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class ProxySettingsResponse(val motd: String?, val maxPlayers: Int?, val forwardingMode: String?)

@Serializable
data class UpdateProxySettingsRequest(val motd: String?, val maxPlayers: Int?, val forwardingMode: String?)

/**
 * Proxy-side settings (MOTD, max players, forwarding mode) stored on the proxy
 * server row. Persisting them marks the proxy for recreate so the rendered patch
 * is applied on next start (issue #36). The actual patch file is written by
 * [ProxyConfigPatchService] (wired in phase 4).
 */
class ProxySettingsService(private val serverRepository: ServerRepository) {

    fun getSettings(proxyServerId: Uuid): ProxySettingsResponse {
        val row = serverRepository.findById(proxyServerId) ?: throw NotFoundException("Server not found")
        if (row.serverType !in PROXY_SERVER_TYPES) throw ConflictException("Server is not a proxy type")
        return ProxySettingsResponse(
            motd = row.proxyMotd,
            maxPlayers = row.proxyMaxPlayers,
            forwardingMode = row.proxyForwardingMode
        )
    }

    fun updateSettings(proxyServerId: Uuid, req: UpdateProxySettingsRequest): ProxySettingsResponse {
        val row = serverRepository.findById(proxyServerId) ?: throw NotFoundException("Server not found")
        if (row.serverType !in PROXY_SERVER_TYPES) throw ConflictException("Server is not a proxy type")

        val mode = req.forwardingMode?.uppercase()
        validateForwardingMode(row.serverType, mode)
        if (req.maxPlayers != null && req.maxPlayers <= 0) {
            throw UnprocessableException("maxPlayers must be greater than 0")
        }

        serverRepository.updateProxySettings(proxyServerId, req.motd, req.maxPlayers, mode)
        serverRepository.updateNeedsRecreate(proxyServerId, true)
        return getSettings(proxyServerId)
    }

    private fun validateForwardingMode(serverType: String, mode: String?) {
        if (mode == null) return
        val allowed = if (serverType == "VELOCITY") VELOCITY_FORWARDING_MODES else BUNGEE_FORWARDING_MODES
        if (mode !in allowed) {
            throw UnprocessableException("forwardingMode must be one of: ${allowed.joinToString()}")
        }
    }

    companion object {
        val VELOCITY_FORWARDING_MODES = setOf("NONE", "LEGACY", "MODERN", "BUNGEEGUARD")
        val BUNGEE_FORWARDING_MODES = setOf("LEGACY", "OFF")
    }
}
