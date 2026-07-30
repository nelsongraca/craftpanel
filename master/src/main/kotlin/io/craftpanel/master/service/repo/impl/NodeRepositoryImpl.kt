package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.schema.*
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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

    override fun getMetrics(nodeId: Uuid, limit: Int): List<NodeMetricsRow> = transaction {
        NodeMetrics.selectAll()
            .where { NodeMetrics.nodeId eq nodeId }
            .orderBy(NodeMetrics.recordedAt, SortOrder.DESC)
            .limit(limit)
            .map {
                NodeMetricsRow(
                    id = it[NodeMetrics.id].value,
                    nodeId = it[NodeMetrics.nodeId].value,
                    recordedAt = it[NodeMetrics.recordedAt].toUtcString(),
                    cpuPercent = it[NodeMetrics.cpuPercent],
                    ramUsedMb = it[NodeMetrics.ramUsedMb],
                    ramTotalMb = it[NodeMetrics.ramTotalMb],
                    netInBytes = it[NodeMetrics.netInBytes],
                    netOutBytes = it[NodeMetrics.netOutBytes],
                    diskUsedBytes = it[NodeMetrics.diskUsedBytes],
                    diskTotalBytes = it[NodeMetrics.diskTotalBytes]
                )
            }
    }
}

private fun ResultRow.toNodeRow() = NodeRow(
    id = this[Nodes.id].value,
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
    reservedRamMb = this[Nodes.reservedRamMb],
    portRangeStart = this[Nodes.portRangeStart],
    portRangeEnd = this[Nodes.portRangeEnd],
    swarmActive = this[Nodes.swarmActive],
    agentVersion = this[Nodes.agentVersion],
    lastSeenAt = this[Nodes.lastSeenAt]?.toUtcString(),
    createdAt = this[Nodes.createdAt].toUtcString(),
    updatedAt = this[Nodes.updatedAt].toUtcString()
)