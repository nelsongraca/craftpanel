package io.craftpanel.master.service

import io.craftpanel.master.service.repo.ProxyBackendInput
import io.craftpanel.master.service.repo.ProxyBackendRow
import io.craftpanel.master.service.repo.ServerRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class ProxyBackendItem(
    val id: String,
    @SerialName("backend_server_id") val backendServerId: String,
    @SerialName("backend_name") val backendName: String,
    val order: Int,
)

@Serializable
data class ProxyBackendListResponse(val backends: List<ProxyBackendItem>)

@Serializable
data class BackendInput(
    @SerialName("backend_server_id") val backendServerId: String,
    @SerialName("backend_name") val backendName: String,
    val order: Int,
)

@Serializable
data class PutProxyBackendsRequest(val backends: List<BackendInput>)

class ProxyBackendService(private val serverRepository: ServerRepository) {

    fun listBackends(proxyServerId: Uuid): ProxyBackendListResponse {
        val serverRow = serverRepository.findById(proxyServerId) ?: throw NotFoundException("Server not found")
        if (serverRow.serverType !in PROXY_SERVER_TYPES) throw ConflictException("Server is not a proxy type")
        return ProxyBackendListResponse(
            serverRepository.listProxyBackends(proxyServerId)
                .map { it.toItem() })
    }

    fun replaceBackends(proxyServerId: Uuid, req: PutProxyBackendsRequest): ProxyBackendListResponse {
        val serverRow = serverRepository.findById(proxyServerId) ?: throw NotFoundException("Server not found")
        if (serverRow.serverType !in PROXY_SERVER_TYPES) throw ConflictException("Server is not a proxy type")

        val names = req.backends.map { it.backendName.trim() }
        if (names.size != names.toSet().size) throw UnprocessableException("Duplicate backend names")

        val inputs = req.backends.map { b ->
            val backendId = runCatching { Uuid.parse(b.backendServerId) }.getOrNull()
                ?: throw UnprocessableException("Invalid backend_server_id: ${b.backendServerId}")
            val backendRow = serverRepository.findById(backendId)
                ?: throw UnprocessableException("Backend server not found: ${b.backendServerId}")
            if (backendRow.serverType in PROXY_SERVER_TYPES)
                throw UnprocessableException("Backend server cannot be a proxy type: ${b.backendServerId}")
            ProxyBackendInput(backendServerId = backendId, backendName = b.backendName.trim(), order = b.order)
        }

        serverRepository.replaceProxyBackends(proxyServerId, inputs)
        return listBackends(proxyServerId)
    }
}

private fun ProxyBackendRow.toItem() = ProxyBackendItem(
    id = id.toString(),
    backendServerId = backendServerId.toString(),
    backendName = backendName,
    order = order,
)
