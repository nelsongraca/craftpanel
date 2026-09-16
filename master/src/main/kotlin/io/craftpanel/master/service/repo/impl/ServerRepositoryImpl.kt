package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.EntityHook
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

class ServerRepositoryImpl :
    AbstractCachedRepository<ServerView>(),
    ServerRepository {

    private val log = LoggerFactory.getLogger(ServerRepositoryImpl::class.java)

    init {
        EntityHook.subscribe { change ->
            try {
                if (change.entityClass == Server) {
                    val id = change.entityId.value as? Uuid ?: return@subscribe
                    invalidate(id)
                }
            } catch (e: Exception) {
                log.error("Failed to invalidate cached server row from EntityHook change", e)
            }
        }
    }

    override fun findById(id: Uuid): ServerView? = cachedFindById(id) {
        transaction {
            Servers.selectAll()
                .where { Servers.id eq id }
                .firstOrNull()
                ?.toServerView()
        }
    }

    override fun findByName(name: String): ServerView? = transaction {
        Servers.selectAll()
            .where { Servers.name eq name }
            .firstOrNull()
            ?.toServerView()
    }

    override fun findBySubdomain(subdomain: String): ServerView? = transaction {
        Servers.selectAll()
            .where { Servers.publicSubdomain eq subdomain }
            .firstOrNull()
            ?.toServerView()
    }

    override fun findByCustomHostname(hostname: String): ServerView? = transaction {
        Servers.selectAll()
            .where { Servers.customHostname eq hostname }
            .firstOrNull()
            ?.toServerView()
    }

    override fun findByDnsRecordName(hostname: String): ServerView? = transaction {
        Servers.selectAll()
            .where { Servers.dnsRecordName eq hostname }
            .firstOrNull()
            ?.toServerView()
    }

    override fun listAll(): List<ServerView> = transaction {
        Servers.selectAll()
            .map { it.toServerView() }
    }

    override fun listByVisibility(networkIds: List<Uuid>, serverIds: List<Uuid>): List<ServerView> = transaction {
        if (networkIds.isEmpty() && serverIds.isEmpty()) return@transaction emptyList()
        Servers.selectAll()
            .where {
                buildList<Op<Boolean>> {
                    if (networkIds.isNotEmpty()) add(Servers.networkId inList networkIds)
                    if (serverIds.isNotEmpty()) add(Servers.id inList serverIds.map { EntityID(it, Servers) })
                }.reduce { a, b -> a or b }
            }
            .map { it.toServerView() }
    }

    override fun listByNetworkId(networkId: Uuid): List<ServerView> = transaction {
        Servers.selectAll()
            .where { Servers.networkId eq networkId }
            .map { it.toServerView() }
    }

    override fun listByNodeId(nodeId: Uuid): List<ServerView> = transaction {
        Servers.selectAll()
            .where { Servers.nodeId eq nodeId }
            .map { it.toServerView() }
    }

    override fun listIds(ids: List<Uuid>): List<ServerView> = transaction {
        Servers.selectAll()
            .where { Servers.id inList ids.map { EntityID(it, Servers) } }
            .map { it.toServerView() }
    }

    override fun listWithBackupSchedule(): List<ServerView> = transaction {
        Servers.selectAll()
            .where { Servers.backupSchedule.isNotNull() }
            .map { it.toServerView() }
    }

    override fun listExpiredRunning(now: kotlinx.datetime.LocalDateTime): List<ServerView> = transaction {
        Servers.selectAll()
            .where {
                Servers.expiresAt.isNotNull() and
                    (Servers.expiresAt lessEq now) and
                    (Servers.status inList listOf("STARTING", "HEALTHY", "UNHEALTHY"))
            }
            .map { it.toServerView() }
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

    override fun updateDesiredStatus(id: Uuid, value: String?) = transaction {
        Server.findById(id)?.let { it.desiredStatus = value }
        Unit
    }

    override fun updateForwardingSecret(id: Uuid, enc: String) = transaction {
        Server.findById(id)?.let { it.forwardingSecretEnc = enc }
        Unit
    }
}

private fun ResultRow.toServerView() = ServerView(
    id = this[Servers.id].value,
    name = this[Servers.name],
    displayName = this[Servers.displayName],
    description = this[Servers.description],
    nodeId = this[Servers.nodeId].value,
    networkId = this[Servers.networkId]?.value,
    serverType = ServerType.fromDb(this[Servers.serverType]),
    mcVersion = this[Servers.mcVersion],
    status = this[Servers.status],
    desiredStatus = this[Servers.desiredStatus],
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
    disabled = this[Servers.disabled],
    expiresAt = this[Servers.expiresAt]?.toUtcString(),
    customServerJar = this[Servers.customServerJar],
    containerListenPort = this[Servers.containerListenPort],
    containerProtocol = this[Servers.containerProtocol],
    disableHealthcheck = this[Servers.disableHealthcheck],
    forceRedownload = this[Servers.forceRedownload],
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
