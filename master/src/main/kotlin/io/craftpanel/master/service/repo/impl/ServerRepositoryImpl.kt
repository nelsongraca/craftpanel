package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.entity.ServerEntity
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ServerRepositoryImpl : ServerRepository {

    override fun findById(id: Uuid): ServerRow? = transaction {
        Servers.selectAll()
            .where { Servers.id eq id }
            .firstOrNull()
            ?.toServerRow()
    }

    override fun findByName(name: String): ServerRow? = transaction {
        Servers.selectAll()
            .where { Servers.name eq name }
            .firstOrNull()
            ?.toServerRow()
    }

    override fun findBySubdomain(subdomain: String): ServerRow? = transaction {
        Servers.selectAll()
            .where { Servers.publicSubdomain eq subdomain }
            .firstOrNull()
            ?.toServerRow()
    }

    override fun findByCustomHostname(hostname: String): ServerRow? = transaction {
        Servers.selectAll()
            .where { Servers.customHostname eq hostname }
            .firstOrNull()
            ?.toServerRow()
    }

    override fun findByDnsRecordName(hostname: String): ServerRow? = transaction {
        Servers.selectAll()
            .where { Servers.dnsRecordName eq hostname }
            .firstOrNull()
            ?.toServerRow()
    }

    override fun listAll(): List<ServerRow> = transaction {
        Servers.selectAll()
            .map { it.toServerRow() }
    }

    override fun listByVisibility(networkIds: List<Uuid>, serverIds: List<Uuid>): List<ServerRow> = transaction {
        if (networkIds.isEmpty() && serverIds.isEmpty()) return@transaction emptyList()
        Servers.selectAll()
            .where {
                buildList<Op<Boolean>> {
                    if (networkIds.isNotEmpty()) add(Servers.networkId inList networkIds)
                    if (serverIds.isNotEmpty()) add(Servers.id inList serverIds.map { EntityID(it, Servers) })
                }.reduce { a, b -> a or b }
            }
            .map { it.toServerRow() }
    }

    override fun listByNetworkId(networkId: Uuid): List<ServerRow> = transaction {
        Servers.selectAll()
            .where { Servers.networkId eq networkId }
            .map { it.toServerRow() }
    }

    override fun listByNodeId(nodeId: Uuid): List<ServerRow> = transaction {
        Servers.selectAll()
            .where { Servers.nodeId eq nodeId }
            .map { it.toServerRow() }
    }

    override fun listIds(ids: List<Uuid>): List<ServerRow> = transaction {
        Servers.selectAll()
            .where { Servers.id inList ids.map { EntityID(it, Servers) } }
            .map { it.toServerRow() }
    }

    override fun listWithBackupSchedule(): List<ServerRow> = transaction {
        Servers.selectAll()
            .where { Servers.backupSchedule.isNotNull() }
            .map { it.toServerRow() }
    }

    override fun countByNetworkId(networkId: Uuid): Int = transaction {
        Servers.selectAll()
            .where { Servers.networkId eq networkId }
            .toList()
            .size
    }

    override fun countByNodeId(nodeId: Uuid): Int = transaction {
        Servers.selectAll()
            .where { Servers.nodeId eq nodeId }
            .toList()
            .size
    }

    override fun updateNeedsRecreate(id: Uuid, value: Boolean) = transaction {
        ServerEntity.findById(id)?.let { it.needsRecreate = value }
        Unit
    }

    override fun updateForwardingSecret(id: Uuid, enc: String) = transaction {
        ServerEntity.findById(id)?.let { it.forwardingSecretEnc = enc }
        Unit
    }

    override fun findIdsNeedingRecreateByNode(nodeId: Uuid): List<Uuid> = transaction {
        Servers.selectAll()
            .where { (Servers.nodeId eq nodeId) and (Servers.needsRecreate eq true) }
            .map { it[Servers.id].value }
    }
}

private fun ResultRow.toServerRow() = ServerRow(
    id = this[Servers.id].value,
    name = this[Servers.name],
    displayName = this[Servers.displayName],
    description = this[Servers.description],
    nodeId = this[Servers.nodeId].value,
    networkId = this[Servers.networkId]?.value,
    serverType = ServerType.fromDb(this[Servers.serverType]),
    mcVersion = this[Servers.mcVersion],
    status = this[Servers.status],
    hostPort = this[Servers.hostPort],
    memoryMb = this[Servers.memoryMb],
    cpuShares = this[Servers.cpuShares],
    exposedExternally = this[Servers.exposedExternally],
    publicSubdomain = this[Servers.publicSubdomain],
    dnsRecordId = this[Servers.dnsRecordId],
    dnsRecordName = this[Servers.dnsRecordName],
    customHostname = this[Servers.customHostname],
    configMode = this[Servers.configMode],
    stopCommand = this[Servers.stopCommand],
    itzgImageTag = this[Servers.itzgImageTag],
    needsRecreate = this[Servers.needsRecreate],
    proxyMotd = this[Servers.proxyMotd],
    proxyMaxPlayers = this[Servers.proxyMaxPlayers],
    proxyForwardingMode = this[Servers.proxyForwardingMode],
    forwardingSecretEnc = this[Servers.forwardingSecretEnc],
    backupSchedule = this[Servers.backupSchedule],
    backupMaxCount = this[Servers.backupMaxCount],
    backupScheduleLastFired = this[Servers.backupScheduleLastFired]?.toUtcString(),
    lastPlayerCount = this[Servers.lastPlayerCount],
    lastPlayerNames = this[Servers.lastPlayerNames],
    lastPlayerUpdate = this[Servers.lastPlayerUpdate]?.toUtcString(),
    lastSeenAt = this[Servers.lastSeenAt]?.toUtcString(),
    createdAt = this[Servers.createdAt].toUtcString(),
    updatedAt = this[Servers.updatedAt].toUtcString()
)
