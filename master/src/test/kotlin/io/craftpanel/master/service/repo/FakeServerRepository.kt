package io.craftpanel.master.service.repo

import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

class FakeServerRepository(private val state: FakeRepositories) : ServerRepository {

    data class MutableServer(
        val id: Uuid,
        var name: String,
        var displayName: String,
        var description: String?,
        var nodeId: Uuid,
        var networkId: Uuid?,
        var serverType: String,
        var mcVersion: String,
        var status: String = "STOPPED",
        var hostPort: Int,
        var memoryMb: Int,
        var cpuShares: Int,
        var exposedExternally: Boolean = false,
        var publicSubdomain: String? = null,
        var dnsRecordId: String? = null,
        var dnsRecordName: String? = null,
        var customHostname: String? = null,
        var configMode: String = "MANAGED",
        var stopCommand: String = "stop",
        var itzgImageTag: String = "latest",
        var needsRecreate: Boolean = false,
        var backupSchedule: String? = null,
        var backupMaxCount: Int = 10,
        var backupScheduleLastFired: String? = null,
        var lastPlayerCount: Int? = null,
        var lastPlayerNames: String? = null,
        var lastPlayerUpdate: String? = null,
        var lastSeenAt: String? = null,
        var createdAt: String = "2025-01-01T00:00:00Z",
        var updatedAt: String = "2025-01-01T00:00:00Z"
    )

    data class MutableMod(
        val id: Uuid,
        val serverId: Uuid,
        val modrinthProjectId: String,
        var displayName: String,
        var pinStrategy: String,
        var pinnedVersionId: String?,
        var installedVersionId: String?,
        val createdAt: String = "2025-01-01T00:00:00Z",
        var updatedAt: String = "2025-01-01T00:00:00Z"
    )

    data class MutableMigration(
        val id: Uuid,
        val serverId: Uuid,
        val sourceNodeId: Uuid,
        val targetNodeId: Uuid,
        var status: String = "PENDING",
        val createdAt: String = "2025-01-01T00:00:00Z",
        var completedAt: String? = null
    )

    data class MutableMigrationStep(
        val id: Uuid,
        val migrationId: Uuid,
        val stepNumber: Int,
        val description: String,
        var status: String = "PENDING",
        var startedAt: String? = null,
        var completedAt: String? = null,
        var errorMessage: String? = null
    )

    data class MutablePort(val nodeId: Uuid, val port: Int, val protocol: String, val serverId: Uuid?)

    data class MutableBackup(
        val id: Uuid,
        val serverId: Uuid,
        val nodeId: Uuid,
        val trigger: String,
        var status: String = "IN_PROGRESS",
        var filePath: String? = null,
        var sizeBytes: Long? = null,
        var errorMessage: String? = null,
        val createdAt: String = "2025-01-01T00:00:00Z",
        var completedAt: String? = null
    )

    data class MutableProxyBackend(val id: Uuid, val proxyServerId: Uuid, val backendServerId: Uuid, val backendName: String, val order: Int)

    data class MutableContainerMetrics(
        val serverId: Uuid,
        val recordedAt: String,
        val cpuPercent: Double,
        val ramUsedMb: Int,
        val netInBytes: Long,
        val netOutBytes: Long,
        val blockInBytes: Long,
        val blockOutBytes: Long
    )

    data class MutableServerJob(val id: Uuid, val serverId: Uuid, val type: String, val cronExpression: String, var enabled: Boolean = true, var lastFiredAt: String? = null)

    override fun findById(id: Uuid): ServerRow? = state.servers[id]?.toRow()
    override fun findByName(name: String): ServerRow? = state.servers.values.firstOrNull { it.name == name }
        ?.toRow()

    override fun findBySubdomain(subdomain: String): ServerRow? = state.servers.values.firstOrNull { it.publicSubdomain == subdomain }
        ?.toRow()

    override fun findByCustomHostname(hostname: String): ServerRow? = state.servers.values.firstOrNull { it.customHostname == hostname }
        ?.toRow()

    override fun findByDnsRecordName(hostname: String): ServerRow? = state.servers.values.firstOrNull { it.dnsRecordName == hostname }
        ?.toRow()

    override fun listAll(): List<ServerRow> = state.servers.values.map { it.toRow() }
    override fun listByVisibility(networkIds: List<Uuid>, serverIds: List<Uuid>): List<ServerRow> = state.servers.values.filter { it.networkId in networkIds || it.id in serverIds }
        .map { it.toRow() }

    override fun listByNetworkId(networkId: Uuid): List<ServerRow> = state.servers.values.filter { it.networkId == networkId }
        .map { it.toRow() }

    override fun listByNodeId(nodeId: Uuid): List<ServerRow> = state.servers.values.filter { it.nodeId == nodeId }
        .map { it.toRow() }

    override fun listIds(ids: List<Uuid>): List<ServerRow> = ids.mapNotNull { state.servers[it]?.toRow() }
    override fun listWithBackupSchedule(): List<ServerRow> = state.servers.values.filter { it.backupSchedule != null }
        .map { it.toRow() }

    override fun countByNetworkId(networkId: Uuid): Int = state.servers.values.count { it.networkId == networkId }
    override fun countByNodeId(nodeId: Uuid): Int = state.servers.values.count { it.nodeId == nodeId }
    override fun findIdsNeedingRecreateByNode(nodeId: Uuid): List<Uuid> = state.servers.values.filter { it.nodeId == nodeId && it.needsRecreate }
        .map { it.id }

    override fun create(
        name: String,
        displayName: String,
        description: String?,
        nodeId: Uuid,
        networkId: Uuid?,
        serverType: String,
        mcVersion: String,
        itzgImageTag: String,
        hostPort: Int,
        memoryMb: Int,
        cpuShares: Int,
        configMode: String,
        stopCommand: String
    ): ServerRow {
        val id = Uuid.random()
        val s = MutableServer(
            id,
            name,
            displayName,
            description,
            nodeId,
            networkId,
            serverType,
            mcVersion,
            hostPort = hostPort,
            memoryMb = memoryMb,
            cpuShares = cpuShares,
            configMode = configMode,
            stopCommand = stopCommand,
            itzgImageTag = itzgImageTag
        )
        state.servers[id] = s
        return s.toRow()
    }

    override fun updateDetails(id: Uuid, displayName: String?, description: String?, networkId: Uuid?, mcVersion: String?, itzgImageTag: String?) {
        val s = state.servers[id] ?: return
        if (displayName != null) s.displayName = displayName
        if (description != null) s.description = description
        if (networkId != null) s.networkId = networkId
        if (mcVersion != null) s.mcVersion = mcVersion
        if (itzgImageTag != null) s.itzgImageTag = itzgImageTag
    }

    override fun clearNetworkId(id: Uuid) {
        state.servers[id]?.networkId = null
    }

    override fun updateResources(id: Uuid, memoryMb: Int, cpuShares: Int, itzgImageTag: String?, needsRecreate: Boolean) {
        val s = state.servers[id] ?: return
        s.memoryMb = memoryMb
        s.cpuShares = cpuShares
        s.needsRecreate = needsRecreate
        if (itzgImageTag != null) s.itzgImageTag = itzgImageTag
    }

    override fun updateStatus(id: Uuid, status: String, lastSeenAt: Instant?) {
        state.servers[id]?.let {
            it.status = status
            if (lastSeenAt != null) it.lastSeenAt = lastSeenAt.toString()
        }
    }

    override fun updateExposure(id: Uuid, exposedExternally: Boolean?, publicSubdomain: String?, customHostname: String?, dnsRecordId: String?, dnsRecordName: String?, needsRecreate: Boolean?) {
        state.servers[id]?.let {
            if (exposedExternally != null) it.exposedExternally = exposedExternally
            if (publicSubdomain != null) it.publicSubdomain = publicSubdomain
            it.customHostname = customHostname
            if (dnsRecordId != null) it.dnsRecordId = dnsRecordId
            if (dnsRecordName != null) it.dnsRecordName = dnsRecordName
            if (needsRecreate != null) it.needsRecreate = needsRecreate
        }
    }

    override fun updateNeedsRecreate(id: Uuid, needsRecreate: Boolean) {
        state.servers[id]?.needsRecreate = needsRecreate
    }

    override fun updatePlayerInfo(id: Uuid, playerCount: Int?, playerNames: String?, lastUpdate: Instant?) {
        state.servers[id]?.let {
            if (playerCount != null) it.lastPlayerCount = playerCount
            if (playerNames != null) it.lastPlayerNames = playerNames
            if (lastUpdate != null) it.lastPlayerUpdate = lastUpdate.toString()
        }
    }

    override fun updateBackupSchedule(id: Uuid, schedule: String?, maxCount: Int?) {
        state.servers[id]?.let {
            if (schedule != null) it.backupSchedule = schedule
            if (maxCount != null) it.backupMaxCount = maxCount
        }
    }

    override fun updateBackupScheduleLastFired(id: Uuid, lastFired: Instant?) {
        state.servers[id]?.backupScheduleLastFired = lastFired?.toString()
    }

    override fun updateConfigMode(id: Uuid, configMode: String) {
        state.servers[id]?.configMode = configMode
    }

    override fun updateStopCommand(id: Uuid, stopCommand: String) {
        state.servers[id]?.stopCommand = stopCommand
    }

    override fun delete(id: Uuid) {
        state.servers.remove(id)
        state.mods.remove(id)
        state.envVars.remove(id)
        state.backups.values.removeAll { it.serverId == id }
        state.containerMetrics.removeAll { it.serverId == id }
        state.ports.removeAll { it.serverId == id }
    }

    override fun nullifyNetworkId(networkId: Uuid) {
        state.servers.values.filter { it.networkId == networkId }
            .forEach { it.networkId = null }
    }

    private fun MutableServer.toRow() = ServerRow(
        id,
        name,
        displayName,
        description,
        nodeId,
        networkId,
        serverType,
        mcVersion,
        status,
        hostPort,
        memoryMb,
        cpuShares,
        exposedExternally,
        publicSubdomain,
        dnsRecordId,
        dnsRecordName,
        customHostname,
        configMode,
        stopCommand,
        itzgImageTag,
        needsRecreate,
        backupSchedule,
        backupMaxCount,
        backupScheduleLastFired,
        lastPlayerCount,
        lastPlayerNames,
        lastPlayerUpdate,
        lastSeenAt,
        createdAt,
        updatedAt
    )
}
