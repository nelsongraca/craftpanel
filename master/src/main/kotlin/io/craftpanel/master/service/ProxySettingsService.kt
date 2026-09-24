package io.craftpanel.master.service

import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.ServerRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

@Serializable
data class ProxySettingsResponse(
    val motd: String?,
    @SerialName("max_players") val maxPlayers: Int?,
    @SerialName("forwarding_mode") val forwardingMode: String?,
    @SerialName("proxy_protocol") val proxyProtocol: Boolean,
    @SerialName("forwarding_warnings") val forwardingWarnings: List<String> = emptyList()
)

@Serializable
data class UpdateProxySettingsRequest(
    val motd: String?,
    @SerialName("max_players") val maxPlayers: Int?,
    @SerialName("forwarding_mode") val forwardingMode: String?,
    // null = leave unchanged (absent for older clients); a boolean sets the proxy's PROXY-protocol listener flag.
    @SerialName("proxy_protocol") val proxyProtocol: Boolean? = null
)

/**
 * Proxy-side settings (MOTD, max players, forwarding mode) stored on the proxy
 * server row. Persisting them marks a restart pending and, if the proxy is
 * currently running, writes the refreshed patch immediately via [ProxyPatchWriter].
 * A forwarding-mode change also fans out matching config to every eligible backend
 * via [BackendForwardingService] (#44) — backends that can't support the mode are
 * warn-skipped and surfaced back to the caller.
 */
class ProxySettingsService(private val serverRepository: ServerRepository, private val proxyPatchWriter: ProxyPatchWriter, private val backendForwardingService: BackendForwardingService) {

    fun getSettings(proxyServerId: Uuid): ProxySettingsResponse {
        val row = serverRepository.requireProxy(proxyServerId)
        return ProxySettingsResponse(
            motd = row.proxyMotd,
            maxPlayers = row.proxyMaxPlayers,
            forwardingMode = row.proxyForwardingMode,
            proxyProtocol = row.proxyProtocol
        )
    }

    suspend fun updateSettings(proxyServerId: Uuid, req: UpdateProxySettingsRequest): ProxySettingsResponse {
        val row = serverRepository.requireProxy(proxyServerId)

        val mode = req.forwardingMode?.uppercase()
        validateForwardingMode(row.serverType, mode)
        if (req.maxPlayers != null && req.maxPlayers <= 0) {
            throw UnprocessableException("maxPlayers must be greater than 0")
        }

        transaction {
            val e = Server.findById(proxyServerId) ?: return@transaction
            e.proxyMotd = req.motd
            e.proxyMaxPlayers = req.maxPlayers
            e.proxyForwardingMode = mode
            req.proxyProtocol?.let { e.proxyProtocol = it }
            e.restartPending = true
        }
        proxyPatchWriter.writeIfRunning(row)

        val warnings = if (mode != null) {
            backendForwardingService.applyToAllBackends(proxyServerId, mode)
                .map { it.reason }
        } else {
            emptyList()
        }
        return getSettings(proxyServerId).copy(forwardingWarnings = warnings)
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
