package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakePortRepository(private val state: FakeRepositories) : PortRepository {

    override fun findUsedPortsOnNode(nodeId: Uuid): List<Int> = state.ports.filter { it.nodeId == nodeId }
        .map { it.port }

    override fun registerPort(nodeId: Uuid, port: Int, protocol: String, serverId: Uuid?) {
        state.ports.add(FakeServerRepository.MutablePort(nodeId, port, protocol, serverId))
    }

    override fun releasePort(nodeId: Uuid, port: Int, protocol: String) {
        state.ports.removeAll { it.nodeId == nodeId && it.port == port && it.protocol == protocol }
    }

    override fun releasePortsForServer(serverId: Uuid) {
        state.ports.removeAll { it.serverId == serverId }
    }

    override fun releasePortsForServerOnNode(serverId: Uuid, nodeId: Uuid) {
        state.ports.removeAll { it.serverId == serverId && it.nodeId == nodeId }
    }
}
