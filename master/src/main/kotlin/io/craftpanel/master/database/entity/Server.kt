package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.ServerView
import io.craftpanel.master.util.toUtcString
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.EntityBatchUpdate
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.time.Clock
import kotlin.uuid.Uuid

class Server(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<Server>(Servers)

    var name by Servers.name
    var displayName by Servers.displayName
    var description by Servers.description
    var nodeId by Servers.nodeId
    var networkId by Servers.networkId
    var serverType by Servers.serverType
    var mcVersion by Servers.mcVersion
    var status by Servers.status
    var desiredStatus by Servers.desiredStatus
    var hostPort by Servers.hostPort
    var memoryMb by Servers.memoryMb
    var cpuLimitMillicores by Servers.cpuLimitMillicores
    var exposedExternally by Servers.exposedExternally
    var publicSubdomain by Servers.publicSubdomain
    var dnsRecordId by Servers.dnsRecordId
    var dnsRecordName by Servers.dnsRecordName
    var customHostname by Servers.customHostname
    var configMode by Servers.configMode
    var stopCommand by Servers.stopCommand
    var itzgImageTag by Servers.itzgImageTag
    var customServerJar by Servers.customServerJar
    var containerListenPort by Servers.containerListenPort
    var containerProtocol by Servers.containerProtocol
    var disableHealthcheck by Servers.disableHealthcheck
    var forceRedownload by Servers.forceRedownload
    var dataDirName by Servers.dataDirName
    var restartPending by Servers.restartPending
    var disabled by Servers.disabled
    var expiresAt by Servers.expiresAt
    var proxyMotd by Servers.proxyMotd
    var proxyMaxPlayers by Servers.proxyMaxPlayers
    var proxyForwardingMode by Servers.proxyForwardingMode
    var proxyProtocol by Servers.proxyProtocol
    var forwardingSecretEnc by Servers.forwardingSecretEnc
    var forwardingPatchFile by Servers.forwardingPatchFile
    var backupSchedule by Servers.backupSchedule
    var backupMaxCount by Servers.backupMaxCount
    var backupScheduleLastFired by Servers.backupScheduleLastFired
    var lastPlayerCount by Servers.lastPlayerCount
    var lastPlayerNames by Servers.lastPlayerNames
    var lastPlayerUpdate by Servers.lastPlayerUpdate
    var lastSeenAt by Servers.lastSeenAt
    var createdAt by Servers.createdAt
    var updatedAt by Servers.updatedAt

    /**
     * Stamp [updatedAt] on every real change, so it tracks the last time this server was modified
     * (status, config, resources, lifecycle, backups, …) rather than only its creation. Exposed's
     * entity cache calls this on flush; [writeValues] is non-empty only when an update is actually
     * pending, so reads/refreshes never bump it.
     */
    override fun flush(batch: EntityBatchUpdate?): Boolean {
        if (writeValues.isNotEmpty()) {
            updatedAt = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        }
        return super.flush(batch)
    }

    fun toServerView() = ServerView(
        id = id.value,
        name = name,
        displayName = displayName,
        description = description,
        nodeId = nodeId.value,
        networkId = networkId?.value,
        serverType = ServerType.fromDb(serverType),
        mcVersion = mcVersion,
        status = status,
        desiredStatus = desiredStatus,
        hostPort = hostPort,
        memoryMb = memoryMb,
        cpuLimitMillicores = cpuLimitMillicores,
        exposedExternally = exposedExternally,
        publicSubdomain = publicSubdomain,
        dnsRecordId = dnsRecordId,
        dnsRecordName = dnsRecordName,
        customHostname = customHostname,
        configMode = configMode,
        stopCommand = stopCommand,
        itzgImageTag = itzgImageTag,
        disabled = disabled,
        expiresAt = expiresAt?.toUtcString(),
        customServerJar = customServerJar,
        containerListenPort = containerListenPort,
        containerProtocol = containerProtocol,
        disableHealthcheck = disableHealthcheck,
        forceRedownload = forceRedownload,
        dataDirName = dataDirName,
        restartPending = restartPending,
        proxyMotd = proxyMotd,
        proxyMaxPlayers = proxyMaxPlayers,
        proxyForwardingMode = proxyForwardingMode,
        proxyProtocol = proxyProtocol,
        forwardingSecretEnc = forwardingSecretEnc,
        forwardingPatchFile = forwardingPatchFile,
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
