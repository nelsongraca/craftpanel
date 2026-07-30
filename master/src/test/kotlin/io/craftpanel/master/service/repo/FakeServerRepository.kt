package io.craftpanel.master.service.repo

import io.craftpanel.master.domain.ServerType
import kotlin.uuid.Uuid

class FakeServerRepository(private val state: FakeRepositories) : ServerRepository {

    data class MutableServer(
        val id: Uuid,
        var name: String,
        var displayName: String,
        var description: String?,
        var nodeId: Uuid,
        var networkId: Uuid?,
        var serverType: ServerType,
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
        var proxyMotd: String? = null,
        var proxyMaxPlayers: Int? = null,
        var proxyForwardingMode: String? = null,
        var forwardingSecretEnc: String? = null,
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
    override fun updateNeedsRecreate(id: Uuid, value: Boolean) {
        state.servers[id]?.needsRecreate = value
    }

    override fun updateForwardingSecret(id: Uuid, enc: String) {
        state.servers[id]?.forwardingSecretEnc = enc
    }

    override fun findIdsNeedingRecreateByNode(nodeId: Uuid): List<Uuid> = state.servers.values.filter { it.nodeId == nodeId && it.needsRecreate }
        .map { it.id }

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
        proxyMotd,
        proxyMaxPlayers,
        proxyForwardingMode,
        forwardingSecretEnc,
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
