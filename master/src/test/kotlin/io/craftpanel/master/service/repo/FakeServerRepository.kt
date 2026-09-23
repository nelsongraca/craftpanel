package io.craftpanel.master.service.repo

import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.util.parseUtcInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.uuid.Uuid

class FakeServerRepository(private val state: FakeRepositories) : ServerRepository {

    data class MutableMod(
        val id: Uuid,
        val serverId: Uuid,
        val modrinthProjectId: String,
        var displayName: String,
        var pinStrategy: String,
        var pinnedVersionId: String?,
        var installedVersionId: String?,
        var enabled: Boolean = true,
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

    override fun findById(id: Uuid): ServerView? = state.servers[id]
    override fun findByName(name: String): ServerView? = state.servers.values.firstOrNull { it.name == name }

    override fun findBySubdomain(subdomain: String): ServerView? = state.servers.values.firstOrNull { it.publicSubdomain == subdomain }

    override fun findByCustomHostname(hostname: String): ServerView? = state.servers.values.firstOrNull { hostname in it.customHostnames() }

    override fun findByDnsRecordName(hostname: String): ServerView? = state.servers.values.firstOrNull { it.dnsRecordName == hostname }

    override fun listAll(): List<ServerView> = state.servers.values.toList()
    override fun listByVisibility(networkIds: List<Uuid>, serverIds: List<Uuid>): List<ServerView> = state.servers.values.filter { it.networkId in networkIds || it.id in serverIds }

    override fun listByNetworkId(networkId: Uuid): List<ServerView> = state.servers.values.filter { it.networkId == networkId }

    override fun listByNodeId(nodeId: Uuid): List<ServerView> = state.servers.values.filter { it.nodeId == nodeId }

    override fun listIds(ids: List<Uuid>): List<ServerView> = ids.mapNotNull { state.servers[it] }
    override fun listWithBackupSchedule(): List<ServerView> = state.servers.values.filter { it.backupSchedule != null }

    override fun listExpiredRunning(now: kotlinx.datetime.LocalDateTime): List<ServerView> = state.servers.values.filter {
        it.expiresAt != null && it.status in setOf("STARTING", "HEALTHY", "UNHEALTHY") &&
            runCatching {
                parseUtcInstant(it.expiresAt)!!.toLocalDateTime(TimeZone.UTC) <= now
            }.getOrDefault(false)
    }

    override fun countByNetworkId(networkId: Uuid): Int = state.servers.values.count { it.networkId == networkId }
    override fun countByNodeId(nodeId: Uuid): Int = state.servers.values.count { it.nodeId == nodeId }
}

/**
 * Builds a [ServerView] with defaults for test setup. Replaces the old `MutableServer` mirror: the
 * fake repository now stores the same immutable projection production returns.
 */
@Suppress("LongParameterList")
fun fakeServerView(
    id: Uuid = Uuid.random(),
    name: String = "server",
    displayName: String = name,
    description: String? = null,
    nodeId: Uuid = Uuid.random(),
    networkId: Uuid? = null,
    serverType: ServerType = ServerType.VANILLA,
    mcVersion: String = "1.21.4",
    status: String = "STOPPED",
    desiredStatus: String? = null,
    hostPort: Int = 25565,
    memoryMb: Int = 1024,
    cpuLimitMillicores: Int = 0,
    exposedExternally: Boolean = false,
    publicSubdomain: String? = null,
    dnsRecordId: String? = null,
    dnsRecordName: String? = null,
    customHostname: String? = null,
    configMode: String = "MANAGED",
    stopCommand: String = "stop",
    itzgImageTag: String = "latest",
    disabled: Boolean = false,
    expiresAt: String? = null,
    customServerJar: String? = null,
    containerListenPort: Int? = null,
    containerProtocol: String = "TCP",
    disableHealthcheck: Boolean = false,
    forceRedownload: Boolean = false,
    restartPending: Boolean = false,
    proxyMotd: String? = null,
    proxyMaxPlayers: Int? = null,
    proxyForwardingMode: String? = null,
    forwardingSecretEnc: String? = null,
    backupSchedule: String? = null,
    backupMaxCount: Int = 10,
    backupScheduleLastFired: String? = null,
    lastPlayerCount: Int? = null,
    lastPlayerNames: String? = null,
    lastPlayerUpdate: String? = null,
    lastSeenAt: String? = null,
    createdAt: String = "2025-01-01T00:00:00Z",
    updatedAt: String = "2025-01-01T00:00:00Z"
) = ServerView(
    id = id,
    name = name,
    displayName = displayName,
    description = description,
    nodeId = nodeId,
    networkId = networkId,
    serverType = serverType,
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
    expiresAt = expiresAt,
    customServerJar = customServerJar,
    containerListenPort = containerListenPort,
    containerProtocol = containerProtocol,
    disableHealthcheck = disableHealthcheck,
    forceRedownload = forceRedownload,
    restartPending = restartPending,
    proxyMotd = proxyMotd,
    proxyMaxPlayers = proxyMaxPlayers,
    proxyForwardingMode = proxyForwardingMode,
    forwardingSecretEnc = forwardingSecretEnc,
    backupSchedule = backupSchedule,
    backupMaxCount = backupMaxCount,
    backupScheduleLastFired = backupScheduleLastFired,
    lastPlayerCount = lastPlayerCount,
    lastPlayerNames = lastPlayerNames,
    lastPlayerUpdate = lastPlayerUpdate,
    lastSeenAt = lastSeenAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)
