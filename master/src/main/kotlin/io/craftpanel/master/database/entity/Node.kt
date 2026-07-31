package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.service.repo.NodeRow
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class Node(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<Node>(Nodes)

    var displayName by Nodes.displayName
    var hostname by Nodes.hostname
    var publicIp by Nodes.publicIp
    var privateIp by Nodes.privateIp
    var tokenHash by Nodes.tokenHash
    var status by Nodes.status
    var health by Nodes.health
    var totalRamMb by Nodes.totalRamMb
    var totalCpuShares by Nodes.totalCpuShares
    var systemRamUsedMb by Nodes.systemRamUsedMb
    var reservedRamMb by Nodes.reservedRamMb
    var portRangeStart by Nodes.portRangeStart
    var portRangeEnd by Nodes.portRangeEnd
    var swarmActive by Nodes.swarmActive
    var agentVersion by Nodes.agentVersion
    var lastSeenAt by Nodes.lastSeenAt
    var createdAt by Nodes.createdAt
    var updatedAt by Nodes.updatedAt

    fun toNodeRow() = NodeRow(
        id = id.value,
        displayName = displayName,
        hostname = hostname,
        publicIp = publicIp,
        privateIp = privateIp,
        tokenHash = tokenHash,
        status = status,
        health = health,
        totalRamMb = totalRamMb,
        totalCpuShares = totalCpuShares,
        systemRamUsedMb = systemRamUsedMb,
        reservedRamMb = reservedRamMb,
        portRangeStart = portRangeStart,
        portRangeEnd = portRangeEnd,
        swarmActive = swarmActive,
        agentVersion = agentVersion,
        lastSeenAt = lastSeenAt?.toUtcString(),
        createdAt = createdAt.toUtcString(),
        updatedAt = updatedAt.toUtcString()
    )
}
