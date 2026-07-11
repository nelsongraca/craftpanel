package io.craftpanel.master.service.repo

import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.util.toUtcString
import kotlinx.datetime.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ServerRepositoryImpl(
    private val envVarsRepository: EnvVarsRepository,
    private val modRepository: ModRepository,
    private val migrationRepository: MigrationRepository,
    private val portRepository: PortRepository,
    private val backupRepository: BackupRepository,
    private val proxyBackendRepository: ProxyBackendRepository,
    private val containerMetricsRepository: ContainerMetricsRepository,
    private val serverJobRepository: ServerJobRepository
) : ServerRepository {

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
        stopCommand: String
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

    override fun updateDetails(id: Uuid, displayName: String?, description: String?, networkId: Uuid?, mcVersion: String?, itzgImageTag: String?) {
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

    override fun updateResources(id: Uuid, memoryMb: Int, cpuShares: Int, itzgImageTag: String?, needsRecreate: Boolean) {
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

    override fun updateExposure(id: Uuid, exposedExternally: Boolean?, publicSubdomain: String?, customHostname: String?, dnsRecordId: String?, dnsRecordName: String?, needsRecreate: Boolean?) {
        transaction {
            Servers.update({ Servers.id eq id }) {
                if (exposedExternally != null) it[Servers.exposedExternally] = exposedExternally
                if (publicSubdomain != null) it[Servers.publicSubdomain] = publicSubdomain
                it[Servers.customHostname] = customHostname
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
            migrationRepository.deleteMigrationStepsForServer(id)
            migrationRepository.deleteMigrationsForServer(id)
            proxyBackendRepository.deleteProxyBackendsForServer(id)
            envVarsRepository.deleteEnvVarsForServer(id)
            modRepository.deleteModsForServer(id)
            backupRepository.deleteBackupsForServer(id)
            containerMetricsRepository.deleteContainerMetricsForServer(id)
            portRepository.releasePortsForServer(id)
            Servers.deleteWhere { Servers.id eq id }
        }
    }

    override fun nullifyNetworkId(networkId: Uuid) {
        transaction { Servers.update({ Servers.networkId eq networkId }) { it[Servers.networkId] = null } }
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
    updatedAt = this[Servers.updatedAt].toString()
)
