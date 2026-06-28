package io.craftpanel.master.service.repo

import io.craftpanel.master.database.schema.NodeMetrics
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.NodeStatus
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.update
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.uuid.Uuid

class NodeRepositoryImpl : NodeRepository {

    override fun findById(id: Uuid): NodeRow? = transaction {
        Nodes.selectAll()
            .where { Nodes.id eq id }
            .firstOrNull()
            ?.toNodeRow()
    }

    override fun findByTokenHash(tokenHash: String): NodeRow? = transaction {
        Nodes.selectAll()
            .where { Nodes.tokenHash eq tokenHash }
            .firstOrNull()
            ?.toNodeRow()
    }

    override fun listAll(): List<NodeRow> = transaction {
        Nodes.selectAll()
            .map { it.toNodeRow() }
    }

    override fun listByIds(ids: List<Uuid>): List<NodeRow> = transaction {
        Nodes.selectAll()
            .where { Nodes.id inList ids }
            .map { it.toNodeRow() }
    }

    override fun create(
        displayName: String,
        hostname: String,
        publicIp: String,
        privateIp: String,
        tokenHash: String,
        portRangeStart: Int,
        portRangeEnd: Int,
    ): NodeRow = transaction {
        val id = Nodes.insert {
            it[Nodes.displayName] = displayName
            it[Nodes.hostname] = hostname
            it[Nodes.publicIp] = publicIp
            it[Nodes.privateIp] = privateIp
            it[Nodes.tokenHash] = tokenHash
            it[Nodes.portRangeStart] = portRangeStart
            it[Nodes.portRangeEnd] = portRangeEnd
        }[Nodes.id]
        Nodes.selectAll()
            .where { Nodes.id eq id }
            .first()
            .toNodeRow()
    }

    override fun update(id: Uuid, displayName: String?, portRangeStart: Int?, portRangeEnd: Int?) {
        transaction {
            Nodes.update({ Nodes.id eq id }) {
                if (displayName != null) it[Nodes.displayName] = displayName
                if (portRangeStart != null) it[Nodes.portRangeStart] = portRangeStart
                if (portRangeEnd != null) it[Nodes.portRangeEnd] = portRangeEnd
            }
        }
    }

    override fun updateStatus(id: Uuid, status: String) {
        transaction { Nodes.update({ Nodes.id eq id }) { it[Nodes.status] = status } }
    }

    override fun updateHealth(id: Uuid, health: String) {
        transaction { Nodes.update({ Nodes.id eq id }) { it[Nodes.health] = health } }
    }

    override fun updateLastSeen(id: Uuid, lastSeenAt: Instant, publicIp: String?, agentVersion: String?) {
        transaction {
            Nodes.update({ Nodes.id eq id }) {
                it[Nodes.lastSeenAt] = lastSeenAt.toLocalDateTime(TimeZone.UTC)
                if (publicIp != null) it[Nodes.publicIp] = publicIp
                if (agentVersion != null) it[Nodes.agentVersion] = agentVersion
            }
        }
    }

    override fun updateSystemRam(id: Uuid, ramUsedMb: Int) {
        transaction { Nodes.update({ Nodes.id eq id }) { it[Nodes.systemRamUsedMb] = ramUsedMb } }
    }

    override fun updateSwarmActive(id: Uuid, swarmActive: Boolean) {
        transaction { Nodes.update({ Nodes.id eq id }) { it[Nodes.swarmActive] = swarmActive } }
    }

    override fun markUnreachable(id: Uuid, lastSeenAt: Instant?) {
        transaction {
            Nodes.update({ Nodes.id eq id }) {
                it[Nodes.health] = "UNREACHABLE"
                if (lastSeenAt != null) it[Nodes.lastSeenAt] = lastSeenAt.toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun markDecommissioned(id: Uuid) {
        transaction { Nodes.update({ Nodes.id eq id }) { it[Nodes.status] = NodeStatus.DECOMMISSIONED.name } }
    }

    override fun updateTokenHash(id: Uuid, tokenHash: String) {
        transaction { Nodes.update({ Nodes.id eq id }) { it[Nodes.tokenHash] = tokenHash } }
    }

    override fun calculateAllocatedRam(id: Uuid): Int = transaction {
        Servers.selectAll()
            .where { Servers.nodeId eq id }
            .sumOf { it[Servers.memoryMb] }
    }

    override fun calculateAllocatedCpu(id: Uuid): Int = transaction {
        Servers.selectAll()
            .where { Servers.nodeId eq id }
            .sumOf { it[Servers.cpuShares] }
    }

    override fun insertMetrics(
        nodeId: Uuid,
        cpuPercent: Double,
        ramUsedMb: Int,
        ramTotalMb: Int,
        netInBytes: Long,
        netOutBytes: Long,
        diskUsedBytes: Long,
        diskTotalBytes: Long,
        recordedAt: Instant,
    ) {
        transaction {
            NodeMetrics.insert {
                it[NodeMetrics.nodeId] = nodeId
                it[NodeMetrics.cpuPercent] = cpuPercent
                it[NodeMetrics.ramUsedMb] = ramUsedMb
                it[NodeMetrics.ramTotalMb] = ramTotalMb
                it[NodeMetrics.netInBytes] = netInBytes
                it[NodeMetrics.netOutBytes] = netOutBytes
                it[NodeMetrics.diskUsedBytes] = diskUsedBytes
                it[NodeMetrics.diskTotalBytes] = diskTotalBytes
                it[NodeMetrics.recordedAt] = recordedAt.toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun getMetrics(nodeId: Uuid, limit: Int): List<NodeMetricsRow> = transaction {
        NodeMetrics.selectAll()
            .where { NodeMetrics.nodeId eq nodeId }
            .orderBy(NodeMetrics.recordedAt, SortOrder.DESC)
            .limit(limit)
            .map {
                NodeMetricsRow(
                    id = it[NodeMetrics.id],
                    nodeId = it[NodeMetrics.nodeId],
                    recordedAt = it[NodeMetrics.recordedAt].toString(),
                    cpuPercent = it[NodeMetrics.cpuPercent],
                    ramUsedMb = it[NodeMetrics.ramUsedMb],
                    ramTotalMb = it[NodeMetrics.ramTotalMb],
                    netInBytes = it[NodeMetrics.netInBytes],
                    netOutBytes = it[NodeMetrics.netOutBytes],
                    diskUsedBytes = it[NodeMetrics.diskUsedBytes],
                    diskTotalBytes = it[NodeMetrics.diskTotalBytes],
                )
            }
    }
}

private fun ResultRow.toNodeRow() = NodeRow(
    id = this[Nodes.id],
    displayName = this[Nodes.displayName],
    hostname = this[Nodes.hostname],
    publicIp = this[Nodes.publicIp],
    privateIp = this[Nodes.privateIp],
    tokenHash = this[Nodes.tokenHash],
    status = this[Nodes.status],
    health = this[Nodes.health],
    totalRamMb = this[Nodes.totalRamMb],
    totalCpuShares = this[Nodes.totalCpuShares],
    systemRamUsedMb = this[Nodes.systemRamUsedMb],
    portRangeStart = this[Nodes.portRangeStart],
    portRangeEnd = this[Nodes.portRangeEnd],
    swarmActive = this[Nodes.swarmActive],
    agentVersion = this[Nodes.agentVersion],
    lastSeenAt = this[Nodes.lastSeenAt]?.toString(),
    createdAt = this[Nodes.createdAt].toUtcString(),
    updatedAt = this[Nodes.updatedAt].toString(),
)
