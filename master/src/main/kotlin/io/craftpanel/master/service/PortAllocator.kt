package io.craftpanel.master.service

import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.PortRepository
import kotlin.uuid.Uuid

/**
 * The one owner of host-port allocation on a node: reads the node's range and its used ports,
 * honours a preferred port when it is free, and otherwise picks the first free port. The node's
 * range is an implementation detail — callers ask only for a port on a node.
 */
class PortAllocator(private val nodeRepository: NodeRepository, private val portRepository: PortRepository) {

    /**
     * Returns [preferred] when it is free, else the first free port in the node's range, else throws
     * [PortExhaustedException].
     */
    fun allocate(nodeId: Uuid, preferred: Int? = null): Int {
        val node = nodeRepository.findById(nodeId) ?: throw NotFoundException("Node not found")
        val usedPorts = portRepository.findUsedPortsOnNode(nodeId).toSet()
        if (preferred != null && preferred !in usedPorts) return preferred
        return pickFreePort(node.portRangeStart, node.portRangeEnd, usedPorts)
            ?: throw PortExhaustedException(
                "No free ports in range ${node.portRangeStart}-${node.portRangeEnd} on node $nodeId"
            )
    }

    companion object {

        fun pickFreePort(portRangeStart: Int, portRangeEnd: Int, usedPorts: Set<Int>): Int? = (portRangeStart..portRangeEnd).firstOrNull { it !in usedPorts }
    }
}
