package io.craftpanel.master.service.repo

import io.craftpanel.master.database.schema.Backups
import io.craftpanel.master.database.schema.ContainerMetrics
import io.craftpanel.master.database.schema.MigrationStepLog
import io.craftpanel.master.database.schema.PortRegistry
import io.craftpanel.master.database.schema.ProxyBackends
import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.database.schema.ServerMigrations
import io.craftpanel.master.database.schema.ServerMods
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.BackupStatus
import io.craftpanel.master.domain.BackupTrigger
import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.domain.MigrationStepStatus
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.core.SortOrder
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
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
                    if (serverIds.isNotEmpty()) add(Servers.id inList serverIds)
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
            .where { Servers.id inList ids }
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

    override fun findIdsNeedingRecreateByNode(nodeId: Uuid): List<Uuid> = transaction {
        Servers.selectAll()
            .where { (Servers.nodeId eq nodeId) and (Servers.needsRecreate eq true) }
            .map { it[Servers.id] }
    }

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
        stopCommand: String,
    ): ServerRow = transaction {
        val id = Servers.insert {
            it[Servers.name] = name
            it[Servers.displayName] = displayName
            it[Servers.description] = description
            it[Servers.nodeId] = nodeId
            it[Servers.networkId] = networkId
            it[Servers.serverType] = serverType
            it[Servers.mcVersion] = mcVersion
            it[Servers.itzgImageTag] = itzgImageTag
            it[Servers.hostPort] = hostPort
            it[Servers.memoryMb] = memoryMb
            it[Servers.cpuShares] = cpuShares
            it[Servers.configMode] = configMode
            it[Servers.stopCommand] = stopCommand
        }[Servers.id]
        Servers.selectAll()
            .where { Servers.id eq id }
            .first()
            .toServerRow()
    }

    override fun updateDetails(
        id: Uuid,
        displayName: String?,
        description: String?,
        networkId: Uuid?,
        mcVersion: String?,
        itzgImageTag: String?,
    ) {
        if (displayName == null && description == null && networkId == null && mcVersion == null && itzgImageTag == null) return
        transaction {
            Servers.update({ Servers.id eq id }) {
                if (displayName != null) it[Servers.displayName] = displayName
                if (description != null) it[Servers.description] = description
                if (networkId != null) it[Servers.networkId] = networkId
                if (mcVersion != null) it[Servers.mcVersion] = mcVersion
                if (itzgImageTag != null) it[Servers.itzgImageTag] = itzgImageTag
            }
        }
    }

    override fun clearNetworkId(id: Uuid) {
        transaction {
            Servers.update({ Servers.id eq id }) { it[Servers.networkId] = null }
        }
    }

    override fun updateResources(
        id: Uuid,
        memoryMb: Int,
        cpuShares: Int,
        itzgImageTag: String?,
        needsRecreate: Boolean,
    ) {
        transaction {
            Servers.update({ Servers.id eq id }) {
                it[Servers.memoryMb] = memoryMb
                it[Servers.cpuShares] = cpuShares
                if (itzgImageTag != null) it[Servers.itzgImageTag] = itzgImageTag
                it[Servers.needsRecreate] = needsRecreate
            }
        }
    }

    override fun updateStatus(id: Uuid, status: String, lastSeenAt: Instant?) {
        transaction {
            Servers.update({ Servers.id eq id }) {
                it[Servers.status] = status
                if (lastSeenAt != null) {
                    it[Servers.lastSeenAt] = lastSeenAt.toLocalDateTime(TimeZone.UTC)
                }
            }
        }
    }

    override fun updateExposure(
        id: Uuid,
        exposedExternally: Boolean?,
        publicSubdomain: String?,
        customHostname: String?,
        dnsRecordId: String?,
        dnsRecordName: String?,
        needsRecreate: Boolean?,
    ) {
        transaction {
            Servers.update({ Servers.id eq id }) {
                if (exposedExternally != null) it[Servers.exposedExternally] = exposedExternally
                if (publicSubdomain != null) it[Servers.publicSubdomain] = publicSubdomain
                if (customHostname != null) it[Servers.customHostname] = customHostname
                if (dnsRecordId != null) it[Servers.dnsRecordId] = dnsRecordId
                if (dnsRecordName != null) it[Servers.dnsRecordName] = dnsRecordName
                if (needsRecreate != null) it[Servers.needsRecreate] = needsRecreate
            }
        }
    }

    override fun updateNeedsRecreate(id: Uuid, needsRecreate: Boolean) {
        transaction { Servers.update({ Servers.id eq id }) { it[Servers.needsRecreate] = needsRecreate } }
    }

    override fun updatePlayerInfo(id: Uuid, playerCount: Int?, playerNames: String?, lastUpdate: Instant?) {
        transaction {
            Servers.update({ Servers.id eq id }) {
                if (playerCount != null) it[Servers.lastPlayerCount] = playerCount
                if (playerNames != null) it[Servers.lastPlayerNames] = playerNames
                if (lastUpdate != null) it[Servers.lastPlayerUpdate] = lastUpdate.toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun updateBackupSchedule(id: Uuid, schedule: String?, maxCount: Int?) {
        if (schedule == null && maxCount == null) return
        transaction {
            Servers.update({ Servers.id eq id }) {
                if (schedule != null) it[Servers.backupSchedule] = schedule
                if (maxCount != null) it[Servers.backupMaxCount] = maxCount
            }
        }
    }

    override fun updateBackupScheduleLastFired(id: Uuid, lastFired: Instant?) {
        transaction {
            Servers.update({ Servers.id eq id }) {
                if (lastFired != null) it[Servers.backupScheduleLastFired] = lastFired.toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun updateConfigMode(id: Uuid, configMode: String) {
        transaction { Servers.update({ Servers.id eq id }) { it[Servers.configMode] = configMode } }
    }

    override fun updateStopCommand(id: Uuid, stopCommand: String) {
        transaction { Servers.update({ Servers.id eq id }) { it[Servers.stopCommand] = stopCommand } }
    }

    override fun delete(id: Uuid) {
        transaction {
            val migrationIds = ServerMigrations.selectAll()
                .where { ServerMigrations.serverId eq id }
                .map { it[ServerMigrations.id] }
            if (migrationIds.isNotEmpty()) {
                MigrationStepLog.deleteWhere { MigrationStepLog.migrationId inList migrationIds }
                ServerMigrations.deleteWhere { ServerMigrations.serverId eq id }
            }
            ProxyBackends.deleteWhere { (ProxyBackends.proxyServerId eq id) or (ProxyBackends.backendServerId eq id) }
            ServerEnvVars.deleteWhere { ServerEnvVars.serverId eq id }
            ServerMods.deleteWhere { ServerMods.serverId eq id }
            Backups.deleteWhere { Backups.serverId eq id }
            ContainerMetrics.deleteWhere { ContainerMetrics.serverId eq id }
            PortRegistry.deleteWhere { PortRegistry.serverId eq id }
            Servers.deleteWhere { Servers.id eq id }
        }
    }

    override fun nullifyNetworkId(networkId: Uuid) {
        transaction { Servers.update({ Servers.networkId eq networkId }) { it[Servers.networkId] = null } }
    }

    override fun getEnvVars(serverId: Uuid): List<EnvVarRow> = transaction {
        ServerEnvVars.selectAll()
            .where { ServerEnvVars.serverId eq serverId }
            .map { EnvVarRow(key = it[ServerEnvVars.key], value = it[ServerEnvVars.value]) }
    }

    override fun replaceEnvVars(serverId: Uuid, envVars: List<EnvVarRow>) {
        transaction {
            ServerEnvVars.deleteWhere { ServerEnvVars.serverId eq serverId }
            envVars.forEach { ev ->
                ServerEnvVars.insert {
                    it[ServerEnvVars.serverId] = serverId
                    it[ServerEnvVars.key] = ev.key
                    it[ServerEnvVars.value] = ev.value
                }
            }
        }
    }

    override fun listMods(serverId: Uuid): List<ModRow> = transaction {
        ServerMods.selectAll()
            .where { ServerMods.serverId eq serverId }
            .map { it.toModRow() }
    }

    override fun findModById(id: Uuid): ModRow? = transaction {
        ServerMods.selectAll()
            .where { ServerMods.id eq id }
            .firstOrNull()
            ?.toModRow()
    }

    override fun findModByProjectId(serverId: Uuid, projectId: String): ModRow? = transaction {
        ServerMods.selectAll()
            .where { (ServerMods.serverId eq serverId) and (ServerMods.modrinthProjectId eq projectId) }
            .firstOrNull()
            ?.toModRow()
    }

    override fun createMod(
        serverId: Uuid,
        modrinthProjectId: String,
        displayName: String,
        pinStrategy: String,
        pinnedVersionId: String?,
        installedVersionId: String?,
    ): ModRow = transaction {
        val id = ServerMods.insert {
            it[ServerMods.serverId] = serverId
            it[ServerMods.modrinthProjectId] = modrinthProjectId
            it[ServerMods.displayName] = displayName
            it[ServerMods.pinStrategy] = pinStrategy
            it[ServerMods.pinnedVersionId] = pinnedVersionId
            it[ServerMods.installedVersionId] = installedVersionId
        }[ServerMods.id]
        ServerMods.selectAll()
            .where { ServerMods.id eq id }
            .first()
            .toModRow()
    }

    override fun updateMod(id: Uuid, pinStrategy: String?, pinnedVersionId: String?, installedVersionId: String?) {
        transaction {
            ServerMods.update({ ServerMods.id eq id }) {
                if (pinStrategy != null) it[ServerMods.pinStrategy] = pinStrategy
                it[ServerMods.pinnedVersionId] = pinnedVersionId?.ifEmpty { null }
                if (installedVersionId != null) it[ServerMods.installedVersionId] = installedVersionId
            }
        }
    }

    override fun deleteMod(id: Uuid) {
        transaction { ServerMods.deleteWhere { ServerMods.id eq id } }
    }

    override fun deleteModsForServer(serverId: Uuid) {
        transaction { ServerMods.deleteWhere { ServerMods.serverId eq serverId } }
    }

    override fun findActiveMigration(serverId: Uuid): MigrationRow? = transaction {
        ServerMigrations.selectAll()
            .where {
                (ServerMigrations.serverId eq serverId) and
                        (ServerMigrations.status inList listOf(MigrationStatus.PENDING.name, MigrationStatus.RUNNING.name, MigrationStatus.SYNCING.name, MigrationStatus.CUTTING_OVER.name))
            }
            .firstOrNull()
            ?.toMigrationRow()
    }

    override fun listMigrations(serverId: Uuid): List<MigrationRow> = transaction {
        ServerMigrations.selectAll()
            .where { ServerMigrations.serverId eq serverId }
            .orderBy(ServerMigrations.createdAt, SortOrder.DESC)
            .map { it.toMigrationRow() }
    }

    override fun findMigrationById(id: Uuid): MigrationRow? = transaction {
        ServerMigrations.selectAll()
            .where { ServerMigrations.id eq id }
            .firstOrNull()
            ?.toMigrationRow()
    }

    override fun createMigration(serverId: Uuid, sourceNodeId: Uuid, targetNodeId: Uuid): MigrationRow = transaction {
        val id = ServerMigrations.insert {
            it[ServerMigrations.serverId] = serverId
            it[ServerMigrations.sourceNodeId] = sourceNodeId
            it[ServerMigrations.targetNodeId] = targetNodeId
            it[ServerMigrations.status] = MigrationStatus.PENDING.name
        }[ServerMigrations.id]
        ServerMigrations.selectAll()
            .where { ServerMigrations.id eq id }
            .first()
            .toMigrationRow()
    }

    override fun updateMigrationStatus(id: Uuid, status: MigrationStatus, completedAt: Instant?) {
        transaction {
            ServerMigrations.update({ ServerMigrations.id eq id }) {
                it[ServerMigrations.status] = status.name
                if (completedAt != null) it[ServerMigrations.completedAt] = completedAt.toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun failMigrationsForNode(nodeId: Uuid) {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        transaction {
            ServerMigrations.update({
                ((ServerMigrations.sourceNodeId eq nodeId) or (ServerMigrations.targetNodeId eq nodeId)) and
                        (ServerMigrations.status inList listOf(MigrationStatus.PENDING.name, MigrationStatus.SYNCING.name, MigrationStatus.CUTTING_OVER.name))
            }) {
                it[ServerMigrations.status] = MigrationStatus.FAILED.name
                it[ServerMigrations.completedAt] = now
            }
        }
    }

    override fun failAllStuckMigrations() {
        transaction {
            ServerMigrations.update({
                ServerMigrations.status inList listOf(MigrationStatus.PENDING.name, MigrationStatus.SYNCING.name, MigrationStatus.CUTTING_OVER.name, MigrationStatus.RUNNING.name)
            }) {
                it[ServerMigrations.status] = MigrationStatus.FAILED.name
                it[ServerMigrations.completedAt] = Clock.System.now()
                    .toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun updateNodeId(id: Uuid, nodeId: Uuid) {
        transaction {
            Servers.update({ Servers.id eq id }) {
                it[Servers.nodeId] = nodeId
                it[Servers.updatedAt] = Clock.System.now()
                    .toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun updateMigrationHostPort(id: Uuid, hostPort: Int) {
        transaction { Servers.update({ Servers.id eq id }) { it[Servers.hostPort] = hostPort } }
    }

    override fun listMigrationSteps(migrationId: Uuid): List<MigrationStepRow> = transaction {
        MigrationStepLog.selectAll()
            .where { MigrationStepLog.migrationId eq migrationId }
            .orderBy(MigrationStepLog.stepNumber, SortOrder.ASC)
            .map { it.toMigrationStepRow() }
    }

    override fun createMigrationStep(migrationId: Uuid, stepNumber: Int, description: String): MigrationStepRow = transaction {
        val id = MigrationStepLog.insert {
            it[MigrationStepLog.migrationId] = migrationId
            it[MigrationStepLog.stepNumber] = stepNumber
            it[MigrationStepLog.description] = description
            it[MigrationStepLog.status] = MigrationStepStatus.PENDING.name
        }[MigrationStepLog.id]
        MigrationStepLog.selectAll()
            .where { MigrationStepLog.id eq id }
            .first()
            .toMigrationStepRow()
    }

    override fun updateMigrationStepStatus(id: Uuid, status: MigrationStepStatus, startedAt: Instant?, completedAt: Instant?, errorMessage: String?) {
        transaction {
            MigrationStepLog.update({ MigrationStepLog.id eq id }) {
                it[MigrationStepLog.status] = status.name
                if (startedAt != null) it[MigrationStepLog.startedAt] = startedAt.toLocalDateTime(TimeZone.UTC)
                if (completedAt != null) it[MigrationStepLog.completedAt] = completedAt.toLocalDateTime(TimeZone.UTC)
                if (errorMessage != null) it[MigrationStepLog.errorMessage] = errorMessage
            }
        }
    }

    override fun findUsedPortsOnNode(nodeId: Uuid): List<Int> = transaction {
        PortRegistry.selectAll()
            .where { PortRegistry.nodeId eq nodeId }
            .map { it[PortRegistry.port] }
    }

    override fun registerPort(nodeId: Uuid, port: Int, protocol: String, serverId: Uuid?) {
        transaction {
            PortRegistry.insert {
                it[PortRegistry.nodeId] = nodeId
                it[PortRegistry.port] = port
                it[PortRegistry.protocol] = protocol
                it[PortRegistry.serverId] = serverId
            }
        }
    }

    override fun releasePort(nodeId: Uuid, port: Int, protocol: String) {
        transaction {
            PortRegistry.deleteWhere {
                (PortRegistry.nodeId eq nodeId) and
                        (PortRegistry.port eq port) and
                        (PortRegistry.protocol eq protocol)
            }
        }
    }

    override fun releasePortsForServer(serverId: Uuid) {
        transaction { PortRegistry.deleteWhere { PortRegistry.serverId eq serverId } }
    }

    override fun releasePortsForServerOnNode(serverId: Uuid, nodeId: Uuid) {
        transaction {
            PortRegistry.deleteWhere {
                (PortRegistry.serverId eq serverId) and (PortRegistry.nodeId eq nodeId)
            }
        }
    }

    override fun listBackups(serverId: Uuid): List<BackupRow> = transaction {
        Backups.selectAll()
            .where { Backups.serverId eq serverId }
            .orderBy(Backups.createdAt, SortOrder.DESC)
            .map { it.toBackupRow() }
    }

    override fun findBackupById(id: Uuid): BackupRow? = transaction {
        Backups.selectAll()
            .where { Backups.id eq id }
            .firstOrNull()
            ?.toBackupRow()
    }

    override fun createBackup(serverId: Uuid, nodeId: Uuid, trigger: BackupTrigger): BackupRow = transaction {
        val id = Backups.insert {
            it[Backups.serverId] = serverId
            it[Backups.nodeId] = nodeId
            it[Backups.trigger] = trigger.name
            it[Backups.status] = BackupStatus.IN_PROGRESS.name
        }[Backups.id]
        Backups.selectAll()
            .where { Backups.id eq id }
            .first()
            .toBackupRow()
    }

    override fun updateBackupStatus(
        id: Uuid,
        status: BackupStatus,
        filePath: String?,
        sizeBytes: Long?,
        errorMessage: String?,
        completedAt: Instant?,
    ) {
        transaction {
            Backups.update({ Backups.id eq id }) {
                it[Backups.status] = status.name
                if (filePath != null) it[Backups.filePath] = filePath
                if (sizeBytes != null) it[Backups.sizeBytes] = sizeBytes
                if (errorMessage != null) it[Backups.errorMessage] = errorMessage
                if (completedAt != null) it[Backups.completedAt] = completedAt.toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun countCompletedBackups(serverId: Uuid): Int = transaction {
        Backups.selectAll()
            .where { (Backups.serverId eq serverId) and (Backups.status eq BackupStatus.COMPLETED.name) }
            .toList()
            .size
    }

    override fun deleteBackup(id: Uuid) {
        transaction { Backups.deleteWhere { Backups.id eq id } }
    }

    override fun deleteBackupsForServer(serverId: Uuid) {
        transaction { Backups.deleteWhere { Backups.serverId eq serverId } }
    }

    override fun failBackupsForNode(nodeId: Uuid) {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        transaction {
            Backups.update({ (Backups.nodeId eq nodeId) and (Backups.status eq BackupStatus.IN_PROGRESS.name) }) {
                it[Backups.status] = BackupStatus.FAILED.name
                it[Backups.errorMessage] = "Node went offline during backup"
                it[Backups.completedAt] = now
            }
        }
    }

    override fun findOldestCompletedBackups(serverId: Uuid, keepCount: Int): List<BackupRow> = transaction {
        val rows = Backups.selectAll()
            .where { (Backups.serverId eq serverId) and (Backups.status eq BackupStatus.COMPLETED.name) }
            .orderBy(Backups.createdAt to SortOrder.ASC)
            .toList()
        if (rows.size < keepCount) emptyList()
        else rows.dropLast(keepCount - 1)
            .map { it.toBackupRow() }
    }

    override fun listProxyBackends(proxyServerId: Uuid): List<ProxyBackendRow> = transaction {
        ProxyBackends.selectAll()
            .where { ProxyBackends.proxyServerId eq proxyServerId }
            .orderBy(ProxyBackends.order, SortOrder.ASC)
            .map { it.toProxyBackendRow() }
    }

    override fun replaceProxyBackends(proxyServerId: Uuid, backends: List<ProxyBackendInput>) {
        transaction {
            ProxyBackends.deleteWhere { ProxyBackends.proxyServerId eq proxyServerId }
            backends.forEach { b ->
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = proxyServerId
                    it[ProxyBackends.backendServerId] = b.backendServerId
                    it[ProxyBackends.backendName] = b.backendName
                    it[ProxyBackends.order] = b.order
                }
            }
        }
    }

    override fun findProxyServersForBackend(backendServerId: Uuid): List<Uuid> = transaction {
        ProxyBackends.selectAll()
            .where { ProxyBackends.backendServerId eq backendServerId }
            .map { it[ProxyBackends.proxyServerId] }
    }

    override fun insertContainerMetrics(
        serverId: Uuid,
        cpuPercent: Double,
        ramUsedMb: Int,
        netInBytes: Long,
        netOutBytes: Long,
        blockInBytes: Long,
        blockOutBytes: Long,
        recordedAt: Instant,
    ) {
        transaction {
            ContainerMetrics.insert {
                it[ContainerMetrics.serverId] = serverId
                it[ContainerMetrics.cpuPercent] = cpuPercent
                it[ContainerMetrics.ramUsedMb] = ramUsedMb
                it[ContainerMetrics.netInBytes] = netInBytes
                it[ContainerMetrics.netOutBytes] = netOutBytes
                it[ContainerMetrics.blockInBytes] = blockInBytes
                it[ContainerMetrics.blockOutBytes] = blockOutBytes
                it[ContainerMetrics.recordedAt] = recordedAt.toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    override fun getContainerMetrics(serverId: Uuid, seconds: Int): List<ContainerMetricsRow> = transaction {
        ContainerMetrics.selectAll()
            .where { (ContainerMetrics.serverId eq serverId) }
            .orderBy(ContainerMetrics.recordedAt to SortOrder.DESC)
            .map { it.toContainerMetricsRow() }
    }

    override fun getContainerMetricsByRange(serverId: Uuid, from: Instant, to: Instant): List<ContainerMetricsRow> = transaction {
        val fromLdt = from.toLocalDateTime(TimeZone.UTC)
        val toLdt = to.toLocalDateTime(TimeZone.UTC)
        ContainerMetrics.selectAll()
            .where {
                (ContainerMetrics.serverId eq serverId) and
                        (ContainerMetrics.recordedAt greaterEq fromLdt) and
                        (ContainerMetrics.recordedAt lessEq toLdt)
            }
            .orderBy(ContainerMetrics.recordedAt to SortOrder.ASC)
            .map { it.toContainerMetricsRow() }
    }

    override fun getLatestContainerMetrics(serverId: Uuid): ContainerMetricsRow? = transaction {
        ContainerMetrics.selectAll()
            .where { ContainerMetrics.serverId eq serverId }
            .orderBy(ContainerMetrics.recordedAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.toContainerMetricsRow()
    }

    override fun getLatestContainerMetricsForServers(serverIds: List<Uuid>): Map<Uuid, ContainerMetricsRow?> = transaction {
        serverIds.associateWith { sid ->
            ContainerMetrics.selectAll()
                .where { ContainerMetrics.serverId eq sid }
                .orderBy(ContainerMetrics.recordedAt to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.toContainerMetricsRow()
        }
    }
}

private fun ResultRow.toServerRow() = ServerRow(
    id = this[Servers.id],
    name = this[Servers.name],
    displayName = this[Servers.displayName],
    description = this[Servers.description],
    nodeId = this[Servers.nodeId],
    networkId = this[Servers.networkId],
    serverType = this[Servers.serverType],
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
    backupSchedule = this[Servers.backupSchedule],
    backupMaxCount = this[Servers.backupMaxCount],
    backupScheduleLastFired = this[Servers.backupScheduleLastFired]?.toString(),
    lastPlayerCount = this[Servers.lastPlayerCount],
    lastPlayerNames = this[Servers.lastPlayerNames],
    lastPlayerUpdate = this[Servers.lastPlayerUpdate]?.toString(),
    lastSeenAt = this[Servers.lastSeenAt]?.toString(),
    createdAt = this[Servers.createdAt].toUtcString(),
    updatedAt = this[Servers.updatedAt].toString(),
)

private fun ResultRow.toModRow() = ModRow(
    id = this[ServerMods.id],
    serverId = this[ServerMods.serverId],
    modrinthProjectId = this[ServerMods.modrinthProjectId],
    displayName = this[ServerMods.displayName],
    pinStrategy = this[ServerMods.pinStrategy],
    pinnedVersionId = this[ServerMods.pinnedVersionId],
    installedVersionId = this[ServerMods.installedVersionId],
    createdAt = this[ServerMods.createdAt].toUtcString(),
    updatedAt = this[ServerMods.updatedAt].toString(),
)

private fun ResultRow.toMigrationRow() = MigrationRow(
    id = this[ServerMigrations.id],
    serverId = this[ServerMigrations.serverId],
    sourceNodeId = this[ServerMigrations.sourceNodeId],
    targetNodeId = this[ServerMigrations.targetNodeId],
    status = this[ServerMigrations.status],
    createdAt = this[ServerMigrations.createdAt].toUtcString(),
    completedAt = this[ServerMigrations.completedAt]?.toUtcString(),
)

private fun ResultRow.toMigrationStepRow() = MigrationStepRow(
    id = this[MigrationStepLog.id],
    migrationId = this[MigrationStepLog.migrationId],
    stepNumber = this[MigrationStepLog.stepNumber],
    description = this[MigrationStepLog.description],
    status = this[MigrationStepLog.status],
    startedAt = this[MigrationStepLog.startedAt]?.toString(),
    completedAt = this[MigrationStepLog.completedAt]?.toString(),
    errorMessage = this[MigrationStepLog.errorMessage],
)

private fun ResultRow.toBackupRow() = BackupRow(
    id = this[Backups.id],
    serverId = this[Backups.serverId],
    nodeId = this[Backups.nodeId],
    trigger = this[Backups.trigger],
    status = this[Backups.status],
    filePath = this[Backups.filePath],
    sizeBytes = this[Backups.sizeBytes],
    errorMessage = this[Backups.errorMessage],
    createdAt = this[Backups.createdAt].toUtcString(),
    completedAt = this[Backups.completedAt]?.toString(),
)

private fun ResultRow.toProxyBackendRow() = ProxyBackendRow(
    id = this[ProxyBackends.id],
    proxyServerId = this[ProxyBackends.proxyServerId],
    backendServerId = this[ProxyBackends.backendServerId],
    backendName = this[ProxyBackends.backendName],
    order = this[ProxyBackends.order],
)

private fun ResultRow.toContainerMetricsRow() = ContainerMetricsRow(
    id = this[ContainerMetrics.id],
    serverId = this[ContainerMetrics.serverId],
    recordedAt = this[ContainerMetrics.recordedAt].toString(),
    cpuPercent = this[ContainerMetrics.cpuPercent],
    ramUsedMb = this[ContainerMetrics.ramUsedMb],
    netInBytes = this[ContainerMetrics.netInBytes],
    netOutBytes = this[ContainerMetrics.netOutBytes],
    blockInBytes = this[ContainerMetrics.blockInBytes],
    blockOutBytes = this[ContainerMetrics.blockOutBytes],
)
