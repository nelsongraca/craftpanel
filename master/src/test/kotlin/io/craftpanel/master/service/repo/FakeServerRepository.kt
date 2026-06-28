package io.craftpanel.master.service.repo

import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

class FakeServerRepository : ServerRepository {

    private val servers = mutableMapOf<Uuid, MutableServer>()
    private val envVars = mutableMapOf<Uuid, MutableList<EnvVarRow>>()
    private val mods = mutableMapOf<Uuid, MutableMap<Uuid, MutableMod>>()
    private val migrations = mutableMapOf<Uuid, MutableMigration>()
    private val steps = mutableMapOf<Uuid, MutableList<MutableMigrationStep>>()
    private val ports = mutableListOf<MutablePort>()
    private val backups = mutableMapOf<Uuid, MutableBackup>()
    private val proxyBackends = mutableMapOf<Uuid, MutableList<MutableProxyBackend>>()
    private val containerMetrics = mutableListOf<MutableContainerMetrics>()

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
        var updatedAt: String = "2025-01-01T00:00:00Z",
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
        var updatedAt: String = "2025-01-01T00:00:00Z",
    )

    data class MutableMigration(
        val id: Uuid,
        val serverId: Uuid,
        val sourceNodeId: Uuid,
        val targetNodeId: Uuid,
        var status: String = "PENDING",
        val createdAt: String = "2025-01-01T00:00:00Z",
        var completedAt: String? = null,
    )

    data class MutableMigrationStep(
        val id: Uuid,
        val migrationId: Uuid,
        val stepNumber: Int,
        val description: String,
        var status: String = "PENDING",
        var startedAt: String? = null,
        var completedAt: String? = null,
        var errorMessage: String? = null,
    )

    data class MutablePort(
        val nodeId: Uuid,
        val port: Int,
        val protocol: String,
        val serverId: Uuid?,
    )

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
        var completedAt: String? = null,
    )

    data class MutableProxyBackend(
        val id: Uuid,
        val proxyServerId: Uuid,
        val backendServerId: Uuid,
        val backendName: String,
        val order: Int,
    )

    data class MutableContainerMetrics(
        val serverId: Uuid,
        val recordedAt: String,
        val cpuPercent: Double,
        val ramUsedMb: Int,
        val netInBytes: Long,
        val netOutBytes: Long,
        val blockInBytes: Long,
        val blockOutBytes: Long,
    )

    override fun findById(id: Uuid): ServerRow? = servers[id]?.toRow()
    override fun findByName(name: String): ServerRow? = servers.values.firstOrNull { it.name == name }
        ?.toRow()

    override fun findBySubdomain(subdomain: String): ServerRow? = servers.values.firstOrNull { it.publicSubdomain == subdomain }
        ?.toRow()

    override fun findByCustomHostname(hostname: String): ServerRow? = servers.values.firstOrNull { it.customHostname == hostname }
        ?.toRow()

    override fun findByDnsRecordName(hostname: String): ServerRow? = servers.values.firstOrNull { it.dnsRecordName == hostname }
        ?.toRow()

    override fun listAll(): List<ServerRow> = servers.values.map { it.toRow() }
    override fun listByVisibility(networkIds: List<Uuid>, serverIds: List<Uuid>): List<ServerRow> =
        servers.values.filter { it.networkId in networkIds || it.id in serverIds }
            .map { it.toRow() }

    override fun listByNetworkId(networkId: Uuid): List<ServerRow> = servers.values.filter { it.networkId == networkId }
        .map { it.toRow() }

    override fun listByNodeId(nodeId: Uuid): List<ServerRow> = servers.values.filter { it.nodeId == nodeId }
        .map { it.toRow() }

    override fun listIds(ids: List<Uuid>): List<ServerRow> = ids.mapNotNull { servers[it]?.toRow() }
    override fun listWithBackupSchedule(): List<ServerRow> = servers.values.filter { it.backupSchedule != null }
        .map { it.toRow() }

    override fun countByNetworkId(networkId: Uuid): Int = servers.values.count { it.networkId == networkId }
    override fun countByNodeId(nodeId: Uuid): Int = servers.values.count { it.nodeId == nodeId }
    override fun findIdsNeedingRecreateByNode(nodeId: Uuid): List<Uuid> = servers.values.filter { it.nodeId == nodeId && it.needsRecreate }
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
        servers[id] = s
        return s.toRow()
    }

    override fun updateDetails(id: Uuid, displayName: String?, description: String?, networkId: Uuid?, mcVersion: String?, itzgImageTag: String?) {
        val s = servers[id] ?: return
        if (displayName != null) s.displayName = displayName
        if (description != null) s.description = description
        if (networkId != null) s.networkId = networkId
        if (mcVersion != null) s.mcVersion = mcVersion
        if (itzgImageTag != null) s.itzgImageTag = itzgImageTag
    }

    override fun clearNetworkId(id: Uuid) {
        servers[id]?.networkId = null
    }

    override fun updateResources(id: Uuid, memoryMb: Int, cpuShares: Int, itzgImageTag: String?, needsRecreate: Boolean) {
        val s = servers[id] ?: return
        s.memoryMb = memoryMb; s.cpuShares = cpuShares; s.needsRecreate = needsRecreate
        if (itzgImageTag != null) s.itzgImageTag = itzgImageTag
    }

    override fun updateStatus(id: Uuid, status: String, lastSeenAt: Instant?) {
        servers[id]?.let { it.status = status; if (lastSeenAt != null) it.lastSeenAt = lastSeenAt.toString() }
    }

    override fun updateExposure(id: Uuid, exposedExternally: Boolean?, publicSubdomain: String?, customHostname: String?, dnsRecordId: String?, dnsRecordName: String?, needsRecreate: Boolean?) {
        servers[id]?.let {
            if (exposedExternally != null) it.exposedExternally = exposedExternally; if (publicSubdomain != null) it.publicSubdomain = publicSubdomain; if (customHostname != null) it.customHostname =
            customHostname; if (dnsRecordId != null) it.dnsRecordId = dnsRecordId; if (dnsRecordName != null) it.dnsRecordName = dnsRecordName; if (needsRecreate != null) it.needsRecreate =
            needsRecreate
        }
    }

    override fun updateNeedsRecreate(id: Uuid, needsRecreate: Boolean) {
        servers[id]?.needsRecreate = needsRecreate
    }

    override fun updatePlayerInfo(id: Uuid, playerCount: Int?, playerNames: String?, lastUpdate: Instant?) {
        servers[id]?.let {
            if (playerCount != null) it.lastPlayerCount = playerCount; if (playerNames != null) it.lastPlayerNames = playerNames; if (lastUpdate != null) it.lastPlayerUpdate = lastUpdate.toString()
        }
    }

    override fun updateBackupSchedule(id: Uuid, schedule: String?, maxCount: Int?) {
        servers[id]?.let { if (schedule != null) it.backupSchedule = schedule; if (maxCount != null) it.backupMaxCount = maxCount }
    }

    override fun updateBackupScheduleLastFired(id: Uuid, lastFired: Instant?) {
        servers[id]?.backupScheduleLastFired = lastFired?.toString()
    }

    override fun updateConfigMode(id: Uuid, configMode: String) {
        servers[id]?.configMode = configMode
    }

    override fun updateStopCommand(id: Uuid, stopCommand: String) {
        servers[id]?.stopCommand = stopCommand
    }

    override fun delete(id: Uuid) {
        servers.remove(id); mods.remove(id); envVars.remove(id); backups.values.removeAll { it.serverId == id }; containerMetrics.removeAll { it.serverId == id }; ports.removeAll { it.serverId == id }
    }

    override fun nullifyNetworkId(networkId: Uuid) {
        servers.values.filter { it.networkId == networkId }
            .forEach { it.networkId = null }
    }

    override fun getEnvVars(serverId: Uuid): List<EnvVarRow> = envVars[serverId]?.toList() ?: emptyList()
    override fun replaceEnvVars(serverId: Uuid, envVars: List<EnvVarRow>) {
        this.envVars[serverId] = envVars.toMutableList()
    }

    override fun listMods(serverId: Uuid): List<ModRow> = mods[serverId]?.values?.map { it.toRow() }
        ?.toList() ?: emptyList()

    override fun findModById(id: Uuid): ModRow? = mods.values.flatMap { it.values }
        .firstOrNull { it.id == id }
        ?.toRow()

    override fun findModByProjectId(serverId: Uuid, projectId: String): ModRow? = mods[serverId]?.values?.firstOrNull { it.modrinthProjectId == projectId }
        ?.toRow()

    override fun createMod(serverId: Uuid, modrinthProjectId: String, displayName: String, pinStrategy: String, pinnedVersionId: String?, installedVersionId: String?): ModRow {
        val id = Uuid.random()
        val m = MutableMod(id, serverId, modrinthProjectId, displayName, pinStrategy, pinnedVersionId, installedVersionId)
        mods.getOrPut(serverId) { mutableMapOf() }[id] = m
        return m.toRow()
    }

    override fun updateMod(id: Uuid, pinStrategy: String?, pinnedVersionId: String?, installedVersionId: String?) {
        val m = mods.values.flatMap { it.values }
            .firstOrNull { it.id == id } ?: return
        if (pinStrategy != null) m.pinStrategy = pinStrategy
        if (pinnedVersionId != null) m.pinnedVersionId = pinnedVersionId
        if (installedVersionId != null) m.installedVersionId = installedVersionId
    }

    override fun deleteMod(id: Uuid) {
        mods.values.forEach { it.remove(id) }
    }

    override fun deleteModsForServer(serverId: Uuid) {
        mods.remove(serverId)
    }

    override fun findActiveMigration(serverId: Uuid): MigrationRow? = migrations.values.firstOrNull { it.serverId == serverId && it.status in listOf("PENDING", "SYNCING", "CUTTING_OVER") }
        ?.toRow()

    override fun listMigrations(serverId: Uuid): List<MigrationRow> = migrations.values.filter { it.serverId == serverId }
        .map { it.toRow() }

    override fun findMigrationById(id: Uuid): MigrationRow? = migrations[id]?.toRow()
    override fun createMigration(serverId: Uuid, sourceNodeId: Uuid, targetNodeId: Uuid): MigrationRow {
        val id = Uuid.random()
        val m = MutableMigration(id, serverId, sourceNodeId, targetNodeId)
        migrations[id] = m
        return m.toRow()
    }

    override fun updateMigrationStatus(id: Uuid, status: String, completedAt: Instant?) {
        migrations[id]?.let { it.status = status; if (completedAt != null) it.completedAt = completedAt.toString() }
    }

    override fun failMigrationsForNode(nodeId: Uuid) {
        migrations.values.filter { (it.sourceNodeId == nodeId || it.targetNodeId == nodeId) && it.status in listOf("PENDING", "SYNCING", "CUTTING_OVER") }
            .forEach { it.status = "FAILED" }
    }

    override fun failAllStuckMigrations() {
        migrations.values.filter { it.status in listOf("PENDING", "SYNCING", "CUTTING_OVER", "RUNNING") }
            .forEach { it.status = "FAILED"; it.completedAt = "now" }
    }

    override fun updateNodeId(id: Uuid, nodeId: Uuid) {
        servers[id]?.nodeId = nodeId
    }

    override fun updateMigrationHostPort(id: Uuid, hostPort: Int) {
        servers[id]?.hostPort = hostPort
    }

    override fun listMigrationSteps(migrationId: Uuid): List<MigrationStepRow> = steps[migrationId]?.map { it.toRow() }
        ?.toList() ?: emptyList()

    override fun createMigrationStep(migrationId: Uuid, stepNumber: Int, description: String): MigrationStepRow {
        val id = Uuid.random()
        val s = MutableMigrationStep(id, migrationId, stepNumber, description)
        steps.getOrPut(migrationId) { mutableListOf() }
            .add(s)
        return s.toRow()
    }

    override fun updateMigrationStepStatus(id: Uuid, status: String, startedAt: Instant?, completedAt: Instant?, errorMessage: String?) {
        val s = steps.values.flatten()
            .firstOrNull { it.id == id } ?: return
        s.status = status
        if (startedAt != null) s.startedAt = startedAt.toString()
        if (completedAt != null) s.completedAt = completedAt.toString()
        if (errorMessage != null) s.errorMessage = errorMessage
    }

    override fun findUsedPortsOnNode(nodeId: Uuid): List<Int> = ports.filter { it.nodeId == nodeId }
        .map { it.port }

    override fun registerPort(nodeId: Uuid, port: Int, protocol: String, serverId: Uuid?) {
        ports.add(MutablePort(nodeId, port, protocol, serverId))
    }

    override fun releasePort(nodeId: Uuid, port: Int, protocol: String) {
        ports.removeAll { it.nodeId == nodeId && it.port == port && it.protocol == protocol }
    }

    override fun releasePortsForServer(serverId: Uuid) {
        ports.removeAll { it.serverId == serverId }
    }

    override fun releasePortsForServerOnNode(serverId: Uuid, nodeId: Uuid) {
        ports.removeAll { it.serverId == serverId && it.nodeId == nodeId }
    }

    override fun listBackups(serverId: Uuid): List<BackupRow> = backups.values.filter { it.serverId == serverId }
        .map { it.toRow() }

    override fun findBackupById(id: Uuid): BackupRow? = backups[id]?.toRow()
    override fun createBackup(serverId: Uuid, nodeId: Uuid, trigger: String): BackupRow {
        val id = Uuid.random()
        val b = MutableBackup(id, serverId, nodeId, trigger)
        backups[id] = b
        return b.toRow()
    }

    override fun updateBackupStatus(id: Uuid, status: String, filePath: String?, sizeBytes: Long?, errorMessage: String?, completedAt: Instant?) {
        backups[id]?.let {
            it.status = status; if (filePath != null) it.filePath = filePath; if (sizeBytes != null) it.sizeBytes = sizeBytes; if (errorMessage != null) it.errorMessage =
            errorMessage; if (completedAt != null) it.completedAt = completedAt.toString()
        }
    }

    override fun countCompletedBackups(serverId: Uuid): Int = backups.values.count { it.serverId == serverId && it.status == "COMPLETED" }
    override fun deleteBackup(id: Uuid) {
        backups.remove(id)
    }

    override fun deleteBackupsForServer(serverId: Uuid) {
        backups.values.removeAll { it.serverId == serverId }
    }

    override fun failBackupsForNode(nodeId: Uuid) {
        backups.values.filter { it.nodeId == nodeId && it.status == "IN_PROGRESS" }
            .forEach { it.status = "FAILED" }
    }

    override fun findOldestCompletedBackups(serverId: Uuid, keepCount: Int): List<BackupRow> {
        val completed = backups.values.filter { it.serverId == serverId && it.status == "COMPLETED" }
            .sortedBy { it.createdAt }
        return if (completed.size <= keepCount) emptyList()
        else completed.dropLast(keepCount)
            .map { it.toRow() }
    }

    override fun listProxyBackends(proxyServerId: Uuid): List<ProxyBackendRow> = proxyBackends[proxyServerId]?.map { it.toRow() }
        ?.toList() ?: emptyList()

    override fun replaceProxyBackends(proxyServerId: Uuid, backends: List<ProxyBackendInput>) {
        proxyBackends[proxyServerId] = backends.mapIndexed { i, b -> MutableProxyBackend(Uuid.random(), proxyServerId, b.backendServerId, b.backendName, b.order) }
            .toMutableList()
    }

    override fun findProxyServersForBackend(backendServerId: Uuid): List<Uuid> = proxyBackends.values.flatten()
        .filter { it.backendServerId == backendServerId }
        .map { it.proxyServerId }

    override fun insertContainerMetrics(serverId: Uuid, cpuPercent: Double, ramUsedMb: Int, netInBytes: Long, netOutBytes: Long, blockInBytes: Long, blockOutBytes: Long, recordedAt: Instant) {
        containerMetrics.add(MutableContainerMetrics(serverId, recordedAt.toString(), cpuPercent, ramUsedMb, netInBytes, netOutBytes, blockInBytes, blockOutBytes))
    }

    override fun getContainerMetrics(serverId: Uuid, seconds: Int): List<ContainerMetricsRow> = containerMetrics.filter { it.serverId == serverId }
        .map { ContainerMetricsRow(Uuid.random(), it.serverId, it.recordedAt, it.cpuPercent, it.ramUsedMb, it.netInBytes, it.netOutBytes, it.blockInBytes, it.blockOutBytes) }

    override fun getContainerMetricsByRange(serverId: Uuid, from: Instant, to: Instant): List<ContainerMetricsRow> =
        containerMetrics.filter { it.serverId == serverId }
            .map { ContainerMetricsRow(Uuid.random(), it.serverId, it.recordedAt, it.cpuPercent, it.ramUsedMb, it.netInBytes, it.netOutBytes, it.blockInBytes, it.blockOutBytes) }

    override fun getLatestContainerMetrics(serverId: Uuid): ContainerMetricsRow? = containerMetrics.filter { it.serverId == serverId }
        .maxByOrNull { it.recordedAt }
        ?.let { ContainerMetricsRow(Uuid.random(), it.serverId, it.recordedAt, it.cpuPercent, it.ramUsedMb, it.netInBytes, it.netOutBytes, it.blockInBytes, it.blockOutBytes) }

    override fun getLatestContainerMetricsForServers(serverIds: List<Uuid>): Map<Uuid, ContainerMetricsRow?> = serverIds.associateWith { getLatestContainerMetrics(it) }

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

    private fun MutableMod.toRow() = ModRow(id, serverId, modrinthProjectId, displayName, pinStrategy, pinnedVersionId, installedVersionId, createdAt, updatedAt)
    private fun MutableMigration.toRow() = MigrationRow(id, serverId, sourceNodeId, targetNodeId, status, createdAt, completedAt)
    private fun MutableMigrationStep.toRow() = MigrationStepRow(id, migrationId, stepNumber, description, status, startedAt, completedAt, errorMessage)
    private fun MutableBackup.toRow() = BackupRow(id, serverId, nodeId, trigger, status, filePath, sizeBytes, errorMessage, createdAt, completedAt)
    private fun MutableProxyBackend.toRow() = ProxyBackendRow(id, proxyServerId, backendServerId, backendName, order)
}
