package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.entity.ServerExtraPort
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.PortRegistry
import io.craftpanel.master.database.schema.ServerExtraPorts
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.service.ConflictException
import io.craftpanel.master.service.PortAllocator
import io.craftpanel.master.service.repo.ServerExtraPortRepository
import io.craftpanel.master.service.repo.ServerExtraPortRow
import io.craftpanel.master.util.toUtcString
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.uuid.Uuid

class ServerExtraPortRepositoryImpl(private val portAllocator: PortAllocator) : ServerExtraPortRepository {

    override fun findByServerId(serverId: Uuid): List<ServerExtraPortRow> = transaction {
        ServerExtraPort.find { ServerExtraPorts.serverId eq serverId }
            .map { it.toRow() }
    }

    override fun createExtraPort(serverId: Uuid, nodeId: Uuid, name: String, containerPort: Int, hostPort: Int?, protocol: String): ServerExtraPortRow = transaction {
        val protoUpper = protocol.uppercase().let { if (it == "UDP") "UDP" else "TCP" }

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

        PortRegistry.insert {
            it[PortRegistry.nodeId] = EntityID(nodeId, Nodes)
            it[PortRegistry.port] = allocatedHostPort
            it[PortRegistry.protocol] = protoUpper
            it[PortRegistry.serverId] = EntityID(serverId, Servers)
        }

        // Port bindings are part of the container spec — flag a restart so the UI can prompt.
        Servers.update({ Servers.id eq serverId }) {
            it[Servers.restartPending] = true
            it[Servers.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
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

        Servers.update({ Servers.id eq sId }) {
            it[Servers.restartPending] = true
            it[Servers.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
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
