package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakePortRepository(private val state: FakeRepositories) : PortRepository {

    override fun findUsedPortsOnNode(nodeId: Uuid): List<Int> = state.ports.filter { it.nodeId == nodeId }
        .map { it.port }
}