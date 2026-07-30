package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

interface PortRepository {

    fun findUsedPortsOnNode(nodeId: Uuid): List<Int>
}
