package io.craftpanel.master.service

import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

private val BACKEND_NAME_PATTERN = Regex("^[A-Za-z0-9_-]+$")

@Serializable
data class ProxyBackendItem(val id: String, @SerialName("backend_server_id") val backendServerId: String, @SerialName("backend_name") val backendName: String, val order: Int)

@Serializable
data class ProxyBackendListResponse(val backends: List<ProxyBackendItem>)

@Serializable
data class BackendInput(@SerialName("backend_server_id") val backendServerId: String, @SerialName("backend_name") val backendName: String, val order: Int)

@Serializable
data class PutProxyBackendsRequest(val backends: List<BackendInput>)

class ProxyBackendService(
    private val serverRepository: ServerRepository,
    private val proxyBackendRepository: ProxyBackendRepository,
    private val proxyConfigPatchService: ProxyConfigPatchService,
    private val writeFile: suspend (Uuid, String, ByteArray) -> Unit
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

        proxyBackendRepository.replaceProxyBackends(proxyServerId, inputs)
        serverRepository.updateNeedsRecreate(proxyServerId, true)
        writePatchIfRunning(serverRow.id, serverRow.status)
        return listBackends(proxyServerId)
    }

    private suspend fun writePatchIfRunning(proxyServerId: Uuid, status: String) {
        if (ServerStatus.fromDb(status) != ServerStatus.HEALTHY) return
        val patch = proxyConfigPatchService.generatePatch(proxyServerId) ?: return
        writeFile(proxyServerId, "craftpanel-patch.json", patch.toByteArray())
    }
}

private fun ProxyBackendRow.toItem() = ProxyBackendItem(
    id = id.toString(),
    backendServerId = backendServerId.toString(),
    backendName = backendName,
    order = order
)
