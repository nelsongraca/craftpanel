package io.craftpanel.master.service.repo

import io.craftpanel.master.domain.BackupStatus
import io.craftpanel.master.domain.BackupTrigger
import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.domain.MigrationStepStatus
import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

data class ServerRow(
    val id: Uuid,
    val name: String,
    val displayName: String,
    val description: String?,
    val nodeId: Uuid,
    val networkId: Uuid?,
    val serverType: String,
    val mcVersion: String,
    val status: String,
    val hostPort: Int,
    val memoryMb: Int,
    val cpuShares: Int,
    val exposedExternally: Boolean,
    val publicSubdomain: String?,
    val dnsRecordId: String?,
    val dnsRecordName: String?,
    val customHostname: String?,
    val configMode: String,
    val stopCommand: String,
    val itzgImageTag: String,
    val needsRecreate: Boolean,
    val backupSchedule: String?,
    val backupMaxCount: Int,
    val backupScheduleLastFired: String?,
    val lastPlayerCount: Int?,
    val lastPlayerNames: String?,
    val lastPlayerUpdate: String?,
    val lastSeenAt: String?,
    val createdAt: String,
    val updatedAt: String
)

data class EnvVarRow(val key: String, val value: String)

data class ModRow(
    val id: Uuid,
    val serverId: Uuid,
    val modrinthProjectId: String,
    val displayName: String,
    val pinStrategy: String,
    val pinnedVersionId: String?,
    val installedVersionId: String?,
    val createdAt: String,
    val updatedAt: String
)

data class MigrationRow(val id: Uuid, val serverId: Uuid, val sourceNodeId: Uuid, val targetNodeId: Uuid, val status: String, val createdAt: String, val completedAt: String?)

data class MigrationStepRow(
    val id: Uuid,
    val migrationId: Uuid,
    val stepNumber: Int,
    val description: String,
    val status: String,
    val startedAt: String?,
    val completedAt: String?,
    val errorMessage: String?
)

data class BackupRow(
    val id: Uuid,
    val serverId: Uuid,
    val nodeId: Uuid,
    val trigger: String,
    val status: String,
    val filePath: String?,
    val sizeBytes: Long?,
    val errorMessage: String?,
    val createdAt: String,
    val completedAt: String?
)

data class ServerJobRow(val id: Uuid, val serverId: Uuid, val type: String, val cronExpression: String, val lastFiredAt: String?)

data class ContainerMetricsRow(
    val id: Uuid,
    val serverId: Uuid,
    val recordedAt: String,
    val cpuPercent: Double,
    val ramUsedMb: Int,
    val netInBytes: Long,
    val netOutBytes: Long,
    val blockInBytes: Long,
    val blockOutBytes: Long
)

interface ServerRepository {

    fun findById(id: Uuid): ServerRow?
    fun findByName(name: String): ServerRow?
    fun findBySubdomain(subdomain: String): ServerRow?
    fun findByCustomHostname(hostname: String): ServerRow?
    fun findByDnsRecordName(hostname: String): ServerRow?
    fun listAll(): List<ServerRow>
    fun listByVisibility(networkIds: List<Uuid>, serverIds: List<Uuid>): List<ServerRow>
    fun listByNetworkId(networkId: Uuid): List<ServerRow>
    fun listByNodeId(nodeId: Uuid): List<ServerRow>
    fun listIds(ids: List<Uuid>): List<ServerRow>
    fun listWithBackupSchedule(): List<ServerRow>
    fun countByNetworkId(networkId: Uuid): Int
    fun countByNodeId(nodeId: Uuid): Int
    fun findIdsNeedingRecreateByNode(nodeId: Uuid): List<Uuid>

    fun create(
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
    ): ServerRow

    fun updateDetails(id: Uuid, displayName: String?, description: String?, networkId: Uuid?, mcVersion: String?, itzgImageTag: String?)
    fun clearNetworkId(id: Uuid)
    fun updateResources(id: Uuid, memoryMb: Int, cpuShares: Int, itzgImageTag: String?, needsRecreate: Boolean)
    fun updateStatus(id: Uuid, status: String, lastSeenAt: Instant?)
    fun updateExposure(id: Uuid, exposedExternally: Boolean?, publicSubdomain: String?, customHostname: String?, dnsRecordId: String?, dnsRecordName: String?, needsRecreate: Boolean?)
    fun updateNeedsRecreate(id: Uuid, needsRecreate: Boolean)
    fun updatePlayerInfo(id: Uuid, playerCount: Int?, playerNames: String?, lastUpdate: Instant?)
    fun updateBackupSchedule(id: Uuid, schedule: String?, maxCount: Int?)
    fun updateBackupScheduleLastFired(id: Uuid, lastFired: Instant?)
    fun updateConfigMode(id: Uuid, configMode: String)
    fun updateStopCommand(id: Uuid, stopCommand: String)
    fun delete(id: Uuid)

    fun nullifyNetworkId(networkId: Uuid)

    fun getEnvVars(serverId: Uuid): List<EnvVarRow>
    fun replaceEnvVars(serverId: Uuid, envVars: List<EnvVarRow>)

    fun listMods(serverId: Uuid): List<ModRow>
    fun findModById(id: Uuid): ModRow?
    fun findModByProjectId(serverId: Uuid, projectId: String): ModRow?
    fun createMod(serverId: Uuid, modrinthProjectId: String, displayName: String, pinStrategy: String, pinnedVersionId: String?, installedVersionId: String?): ModRow

    fun updateMod(id: Uuid, pinStrategy: String?, pinnedVersionId: String?, installedVersionId: String?)
    fun deleteMod(id: Uuid)
    fun deleteModsForServer(serverId: Uuid)

    fun findActiveMigration(serverId: Uuid): MigrationRow?
    fun listMigrations(serverId: Uuid): List<MigrationRow>
    fun findMigrationById(id: Uuid): MigrationRow?
    fun createMigration(serverId: Uuid, sourceNodeId: Uuid, targetNodeId: Uuid): MigrationRow
    fun updateMigrationStatus(id: Uuid, status: MigrationStatus, completedAt: Instant?)
    fun failMigrationsForNode(nodeId: Uuid)
    fun failAllStuckMigrations()
    fun updateNodeId(id: Uuid, nodeId: Uuid)
    fun updateMigrationHostPort(id: Uuid, hostPort: Int)

    fun listMigrationSteps(migrationId: Uuid): List<MigrationStepRow>
    fun createMigrationStep(migrationId: Uuid, stepNumber: Int, description: String): MigrationStepRow
    fun updateMigrationStepStatus(id: Uuid, status: MigrationStepStatus, startedAt: Instant?, completedAt: Instant?, errorMessage: String?)

    fun findUsedPortsOnNode(nodeId: Uuid): List<Int>
    fun registerPort(nodeId: Uuid, port: Int, protocol: String, serverId: Uuid?)
    fun releasePort(nodeId: Uuid, port: Int, protocol: String)
    fun releasePortsForServer(serverId: Uuid)
    fun releasePortsForServerOnNode(serverId: Uuid, nodeId: Uuid)

    fun listBackups(serverId: Uuid): List<BackupRow>
    fun findBackupById(id: Uuid): BackupRow?
    fun createBackup(serverId: Uuid, nodeId: Uuid, trigger: BackupTrigger): BackupRow
    fun updateBackupStatus(id: Uuid, status: BackupStatus, filePath: String?, sizeBytes: Long?, errorMessage: String?, completedAt: Instant?)
    fun countCompletedBackups(serverId: Uuid): Int
    fun deleteBackup(id: Uuid)
    fun deleteBackupsForServer(serverId: Uuid)
    fun failBackupsForNode(nodeId: Uuid)
    fun findOldestCompletedBackups(serverId: Uuid, keepCount: Int): List<BackupRow>

    fun listProxyBackends(proxyServerId: Uuid): List<ProxyBackendRow>
    fun replaceProxyBackends(proxyServerId: Uuid, backends: List<ProxyBackendInput>)
    fun findProxyServersForBackend(backendServerId: Uuid): List<Uuid>

    fun insertContainerMetrics(serverId: Uuid, cpuPercent: Double, ramUsedMb: Int, netInBytes: Long, netOutBytes: Long, blockInBytes: Long, blockOutBytes: Long, recordedAt: Instant)
    fun getContainerMetrics(serverId: Uuid, seconds: Int): List<ContainerMetricsRow>
    fun getContainerMetricsByRange(serverId: Uuid, from: Instant, to: Instant): List<ContainerMetricsRow>
    fun getLatestContainerMetrics(serverId: Uuid): ContainerMetricsRow?
    fun getLatestContainerMetricsForServers(serverIds: List<Uuid>): Map<Uuid, ContainerMetricsRow?>

    fun listEnabledServerJobs(): List<ServerJobRow>
    fun updateServerJobLastFired(jobId: Uuid, lastFired: Instant)
}

data class ProxyBackendRow(val id: Uuid, val proxyServerId: Uuid, val backendServerId: Uuid, val backendName: String, val order: Int)

data class ProxyBackendInput(val backendServerId: Uuid, val backendName: String, val order: Int)
