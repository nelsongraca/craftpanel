package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.ServerRow
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class ServerEntity(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<ServerEntity>(Servers)

    var name by Servers.name
    var displayName by Servers.displayName
    var description by Servers.description
    var nodeId by Servers.nodeId
    var networkId by Servers.networkId
    var serverType by Servers.serverType
    var mcVersion by Servers.mcVersion
    var status by Servers.status
    var hostPort by Servers.hostPort
    var memoryMb by Servers.memoryMb
    var cpuShares by Servers.cpuShares
    var exposedExternally by Servers.exposedExternally
    var publicSubdomain by Servers.publicSubdomain
    var dnsRecordId by Servers.dnsRecordId
    var dnsRecordName by Servers.dnsRecordName
    var customHostname by Servers.customHostname
    var configMode by Servers.configMode
    var stopCommand by Servers.stopCommand
    var itzgImageTag by Servers.itzgImageTag
    var needsRecreate by Servers.needsRecreate
    var proxyMotd by Servers.proxyMotd
    var proxyMaxPlayers by Servers.proxyMaxPlayers
    var proxyForwardingMode by Servers.proxyForwardingMode
    var forwardingSecretEnc by Servers.forwardingSecretEnc
    var backupSchedule by Servers.backupSchedule
    var backupMaxCount by Servers.backupMaxCount
    var backupScheduleLastFired by Servers.backupScheduleLastFired
    var lastPlayerCount by Servers.lastPlayerCount
    var lastPlayerNames by Servers.lastPlayerNames
    var lastPlayerUpdate by Servers.lastPlayerUpdate
    var lastSeenAt by Servers.lastSeenAt
    var createdAt by Servers.createdAt
    var updatedAt by Servers.updatedAt

    fun toServerRow() = ServerRow(
        id = id.value,
        name = name,
        displayName = displayName,
        description = description,
        nodeId = nodeId.value,
        networkId = networkId?.value,
        serverType = ServerType.fromDb(serverType),
        mcVersion = mcVersion,
        status = status,
        hostPort = hostPort,
        memoryMb = memoryMb,
        cpuShares = cpuShares,
        exposedExternally = exposedExternally,
        publicSubdomain = publicSubdomain,
        dnsRecordId = dnsRecordId,
        dnsRecordName = dnsRecordName,
        customHostname = customHostname,
        configMode = configMode,
        stopCommand = stopCommand,
        itzgImageTag = itzgImageTag,
        needsRecreate = needsRecreate,
        proxyMotd = proxyMotd,
        proxyMaxPlayers = proxyMaxPlayers,
        proxyForwardingMode = proxyForwardingMode,
        forwardingSecretEnc = forwardingSecretEnc,
        backupSchedule = backupSchedule,
        backupMaxCount = backupMaxCount,
        backupScheduleLastFired = backupScheduleLastFired?.toUtcString(),
        lastPlayerCount = lastPlayerCount,
        lastPlayerNames = lastPlayerNames,
        lastPlayerUpdate = lastPlayerUpdate?.toUtcString(),
        lastSeenAt = lastSeenAt?.toUtcString(),
        createdAt = createdAt.toUtcString(),
        updatedAt = updatedAt.toUtcString()
    )
}
