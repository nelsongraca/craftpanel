package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.entity.ServerExtraPort
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.PortRegistry
import io.craftpanel.master.database.schema.ServerExtraPorts
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.service.ConflictException
import io.craftpanel.master.service.NotFoundException
import io.craftpanel.master.service.PortAllocator
import io.craftpanel.master.service.repo.ServerExtraPortRepository
import io.craftpanel.master.service.repo.ServerExtraPortRow
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

class ServerExtraPortRepositoryImpl : ServerExtraPortRepository {

    override fun findByServerId(serverId: Uuid): List<ServerExtraPortRow> = transaction {
        ServerExtraPort.find { ServerExtraPorts.serverId eq serverId }
            .map { it.toRow() }
    }

    override fun createExtraPort(serverId: Uuid, nodeId: Uuid, name: String, containerPort: Int, hostPort: Int?, protocol: String): ServerExtraPortRow = transaction {
        val protoUpper = protocol.uppercase().let { if (it == "UDP") "UDP" else "TCP" }

        val nodeRow = Nodes.selectAll().where { Nodes.id eq nodeId }.singleOrNull()
            ?: throw NotFoundException("Node not found")
        val rangeStart = nodeRow[Nodes.portRangeStart]
        val rangeEnd = nodeRow[Nodes.portRangeEnd]

        val usedPorts = PortRegistry.selectAll()
            .where { PortRegistry.nodeId eq nodeId }
            .map { it[PortRegistry.port] }
            .toSet()

        val allocatedHostPort = if (hostPort != null && hostPort > 0) {
            if (usedPorts.contains(hostPort)) {
                throw ConflictException("Port $hostPort is already in use on this node")
            }
            hostPort
        } else {
            PortAllocator.pickFreePort(rangeStart, rangeEnd, usedPorts)
                ?: throw ConflictException("No free ports available on node")
        }

        val entity = ServerExtraPort.new {
            this.serverId = EntityID(serverId, Servers)
            this.nodeId = EntityID(nodeId, Nodes)
            this.name = name
            this.containerPort = containerPort
            this.hostPort = allocatedHostPort
            this.protocol = protoUpper
        }

        PortRegistry.insert {
            it[PortRegistry.nodeId] = EntityID(nodeId, Nodes)
            it[PortRegistry.port] = allocatedHostPort
            it[PortRegistry.protocol] = protoUpper
            it[PortRegistry.serverId] = EntityID(serverId, Servers)
        }

        // Mark server as needing recreate when ports change
        Servers.update({ Servers.id eq serverId }) {
            it[needsRecreate] = true
        }

        entity.toRow()
    }

    override fun deleteExtraPort(portId: Uuid): Boolean = transaction {
        val entity = ServerExtraPort.findById(portId) ?: return@transaction false
        val sId = entity.serverId.value
        val nId = entity.nodeId.value
        val hPort = entity.hostPort
        val proto = entity.protocol

        PortRegistry.deleteWhere {
            (PortRegistry.nodeId eq nId) and (PortRegistry.port eq hPort) and (PortRegistry.protocol eq proto)
        }

        entity.delete()

        // Mark server as needing recreate when ports change
        Servers.update({ Servers.id eq sId }) {
            it[needsRecreate] = true
        }

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
