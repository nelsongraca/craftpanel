package io.craftpanel.master.service

import io.craftpanel.master.database.entity.ProxyBackend
import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.ProxyBackends
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.service.repo.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val BACKEND_NAME_PATTERN = Regex("^[A-Za-z0-9_-]+$")

@Serializable
data class ProxyBackendItem(val id: String, @SerialName("backend_server_id") val backendServerId: String, @SerialName("backend_name") val backendName: String, val order: Int)

@Serializable
data class ProxyBackendListResponse(val backends: List<ProxyBackendItem>, @SerialName("forwarding_warnings") val forwardingWarnings: List<String> = emptyList())

@Serializable
data class BackendInput(@SerialName("backend_server_id") val backendServerId: String, @SerialName("backend_name") val backendName: String, val order: Int)

@Serializable
data class PutProxyBackendsRequest(val backends: List<BackendInput>)

class ProxyBackendService(
    private val serverRepository: ServerRepository,
    private val proxyBackendRepository: ProxyBackendRepository,
    private val proxyPatchWriter: ProxyPatchWriter,
    private val backendForwardingService: BackendForwardingService
) {

    fun listBackends(proxyServerId: Uuid): ProxyBackendListResponse {
        val serverRow = serverRepository.findById(proxyServerId) ?: throw NotFoundException("Server not found")
        if (!serverRow.serverType.isProxy) throw ConflictException("Server is not a proxy type")
        return ProxyBackendListResponse(
            proxyBackendRepository.listProxyBackends(proxyServerId)
                .map { it.toItem() }
        )
    }

    suspend fun replaceBackends(proxyServerId: Uuid, req: PutProxyBackendsRequest): ProxyBackendListResponse {
        val serverRow = serverRepository.findById(proxyServerId) ?: throw NotFoundException("Server not found")
        if (!serverRow.serverType.isProxy) throw ConflictException("Server is not a proxy type")

        val names = req.backends.map { it.backendName.trim() }
        if (names.size != names.toSet().size) throw UnprocessableException("Duplicate backend names")

        val inputs = req.backends.map { b ->
            val name = b.backendName.trim()
            if (!BACKEND_NAME_PATTERN.matches(name)) {
                throw UnprocessableException("Invalid backend_name: must match [A-Za-z0-9_-]+")
            }
            val backendId = runCatching { Uuid.parse(b.backendServerId) }.getOrNull()
                ?: throw UnprocessableException("Invalid backend_server_id: ${b.backendServerId}")
            val backendRow = serverRepository.findById(backendId)
                ?: throw UnprocessableException("Backend server not found: ${b.backendServerId}")
            if (backendRow.serverType.isProxy) {
                throw UnprocessableException("Backend server cannot be a proxy type: ${b.backendServerId}")
            }
            ProxyBackendInput(backendServerId = backendId, backendName = name, order = b.order)
        }

        transaction {
            ProxyBackend.find { ProxyBackends.proxyServerId eq proxyServerId }
                .forEach { it.delete() }
            inputs.forEach { b ->
                ProxyBackend.new {
                    this.proxyServerId = EntityID(proxyServerId, Servers)
                    this.backendServerId = EntityID(b.backendServerId, Servers)
                    this.backendName = b.backendName
                    this.order = b.order
                }
            }
            Server.findById(proxyServerId)
                ?.let { it.restartPending = true }
        }
        proxyPatchWriter.writeIfRunning(serverRow)

        // New/changed backend set on an already-forwarding proxy needs matching config pushed (#44).
        val warnings = serverRow.proxyForwardingMode?.let { mode ->
            backendForwardingService.applyToAllBackends(proxyServerId, mode)
                .map { it.reason }
        } ?: emptyList()
        return listBackends(proxyServerId).copy(forwardingWarnings = warnings)
    }
}

private fun ProxyBackendRow.toItem() = ProxyBackendItem(
    id = id.toString(),
    backendServerId = backendServerId.toString(),
    backendName = backendName,
    order = order
)
