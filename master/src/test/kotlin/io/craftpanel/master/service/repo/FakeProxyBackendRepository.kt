package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakeProxyBackendRepository(private val state: FakeRepositories) : ProxyBackendRepository {

    override fun listProxyBackends(proxyServerId: Uuid): List<ProxyBackendRow> = state.proxyBackends[proxyServerId]?.map { it.toRow() }
        ?.toList() ?: emptyList()

    override fun replaceProxyBackends(proxyServerId: Uuid, backends: List<ProxyBackendInput>) {
        state.proxyBackends[proxyServerId] = backends.mapIndexed { i, b -> FakeServerRepository.MutableProxyBackend(Uuid.random(), proxyServerId, b.backendServerId, b.backendName, b.order) }
            .toMutableList()
    }

    override fun findProxyServersForBackend(backendServerId: Uuid): List<Uuid> = state.proxyBackends.values.flatten()
        .filter { it.backendServerId == backendServerId }
        .map { it.proxyServerId }

    override fun deleteProxyBackendsForServer(serverId: Uuid) {
        state.proxyBackends.remove(serverId)
    }

    private fun FakeServerRepository.MutableProxyBackend.toRow() = ProxyBackendRow(id, proxyServerId, backendServerId, backendName, order)
}
