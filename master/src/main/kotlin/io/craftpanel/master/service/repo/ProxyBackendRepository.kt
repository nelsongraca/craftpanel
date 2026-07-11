package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

data class ProxyBackendRow(val id: Uuid, val proxyServerId: Uuid, val backendServerId: Uuid, val backendName: String, val order: Int)

data class ProxyBackendInput(val backendServerId: Uuid, val backendName: String, val order: Int)

interface ProxyBackendRepository {

    fun listProxyBackends(proxyServerId: Uuid): List<ProxyBackendRow>
    fun replaceProxyBackends(proxyServerId: Uuid, backends: List<ProxyBackendInput>)
    fun findProxyServersForBackend(backendServerId: Uuid): List<Uuid>
    fun deleteProxyBackendsForServer(serverId: Uuid)
}
