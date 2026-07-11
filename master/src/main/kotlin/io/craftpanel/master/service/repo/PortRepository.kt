package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

interface PortRepository {

    fun findUsedPortsOnNode(nodeId: Uuid): List<Int>
    fun registerPort(nodeId: Uuid, port: Int, protocol: String, serverId: Uuid?)
    fun releasePort(nodeId: Uuid, port: Int, protocol: String)
    fun releasePortsForServer(serverId: Uuid)
    fun releasePortsForServerOnNode(serverId: Uuid, nodeId: Uuid)
}
