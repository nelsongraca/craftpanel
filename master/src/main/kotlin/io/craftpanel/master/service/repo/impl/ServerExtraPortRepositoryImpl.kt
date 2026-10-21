package io.craftpanel.master.service.repo.impl

import io.craftpanel.common.PortProtocols
import io.craftpanel.master.database.entity.ServerExtraPort
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.PortRegistry
import io.craftpanel.master.database.schema.ServerExtraPorts
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.service.ConflictException
import io.craftpanel.master.service.PortAllocator
import io.craftpanel.master.service.ServerPortRegistry
import io.craftpanel.master.service.repo.ServerExtraPortRepository
import io.craftpanel.master.service.repo.ServerExtraPortRow
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ServerExtraPortRepositoryImpl(private val portAllocator: PortAllocator) : ServerExtraPortRepository {

    override fun findByServerId(serverId: Uuid): List<ServerExtraPortRow> = transaction {
        ServerExtraPort.find { ServerExtraPorts.serverId eq serverId }
            .map { it.toRow() }
    }

    override fun createExtraPort(serverId: Uuid, nodeId: Uuid, name: String, containerPort: Int, hostPort: Int?, protocol: String): ServerExtraPortRow = transaction {
        // The route validates the value; the fallback keeps older callers (and pre-BOTH rows) on TCP.
        val protoUpper = PortProtocols.normalize(protocol) ?: PortProtocols.TCP

        val allocatedHostPort = if (hostPort != null && hostPort > 0) {
            val usedPorts = PortRegistry.selectAll()
                .where { PortRegistry.nodeId eq nodeId }
                .map { it[PortRegistry.port] }
                .toSet()
            if (usedPorts.contains(hostPort)) {
                throw ConflictException("Port $hostPort is already in use on this node")
            }
            hostPort
        } else {
            portAllocator.allocate(nodeId)
        }

        val entity = ServerExtraPort.new {
            this.serverId = EntityID(serverId, Servers)
            this.nodeId = EntityID(nodeId, Nodes)
            this.name = name
            this.containerPort = containerPort
            this.hostPort = allocatedHostPort
            this.protocol = protoUpper
        }

        ServerPortRegistry.register(nodeId, serverId, allocatedHostPort, protoUpper)

        entity.toRow()
    }

    override fun deleteExtraPort(portId: Uuid): Boolean = transaction {
        val entity = ServerExtraPort.findById(portId) ?: return@transaction false
        // Drop every protocol row for this mapping — a BOTH mapping owns two.
        ServerPortRegistry.unregister(entity.serverId.value, entity.hostPort)

        entity.delete()

        true
    }

    private fun ServerExtraPort.toRow(): ServerExtraPortRow = ServerExtraPortRow(
        id = id.value,
        serverId = serverId.value,
        nodeId = nodeId.value,
        name = name,
        containerPort = containerPort,
        hostPort = hostPort,
        protocol = protocol,
        createdAt = createdAt.toUtcString(),
        updatedAt = updatedAt.toUtcString()
    )
}
