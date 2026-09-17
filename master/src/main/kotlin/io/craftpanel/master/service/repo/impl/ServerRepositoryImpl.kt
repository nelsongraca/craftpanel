package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.service.repo.AbstractCachedRepository
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.ServerView
import io.craftpanel.master.service.repo.parseCustomHostnames
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.dao.EntityHook
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
                log.error("Failed to invalidate cached server view from EntityHook change", e)
            }
        }
    }

    override fun findById(id: Uuid): ServerView? = cachedFindById(id) {
        transaction { Server.findById(id)?.toServerView() }
    }

    override fun findByName(name: String): ServerView? = transaction {
        Server.find { Servers.name eq name }.firstOrNull()?.toServerView()
    }

    override fun findBySubdomain(subdomain: String): ServerView? = transaction {
        Server.find { Servers.publicSubdomain eq subdomain }.firstOrNull()?.toServerView()
    }

    // custom_hostname holds a comma-separated list, so membership is checked after parsing rather
    // than with an equality predicate.
    override fun findByCustomHostname(hostname: String): ServerView? = transaction {
        Server.find { Servers.customHostname.isNotNull() }
            .firstOrNull { hostname in parseCustomHostnames(it.customHostname) }
            ?.toServerView()
    }

    override fun findByDnsRecordName(hostname: String): ServerView? = transaction {
        Server.find { Servers.dnsRecordName eq hostname }.firstOrNull()?.toServerView()
    }

    override fun listAll(): List<ServerView> = transaction {
        Server.all().map { it.toServerView() }
    }

    override fun listByVisibility(networkIds: List<Uuid>, serverIds: List<Uuid>): List<ServerView> = transaction {
        if (networkIds.isEmpty() && serverIds.isEmpty()) return@transaction emptyList()
        Server.find {
            buildList<Op<Boolean>> {
                if (networkIds.isNotEmpty()) add(Servers.networkId inList networkIds)
                if (serverIds.isNotEmpty()) add(Servers.id inList serverIds.map { EntityID(it, Servers) })
            }.reduce { a, b -> a or b }
        }.map { it.toServerView() }
    }

    override fun listByNetworkId(networkId: Uuid): List<ServerView> = transaction {
        Server.find { Servers.networkId eq networkId }.map { it.toServerView() }
    }

    override fun listByNodeId(nodeId: Uuid): List<ServerView> = transaction {
        Server.find { Servers.nodeId eq nodeId }.map { it.toServerView() }
    }

    override fun listIds(ids: List<Uuid>): List<ServerView> = transaction {
        Server.find { Servers.id inList ids.map { EntityID(it, Servers) } }.map { it.toServerView() }
    }

    override fun listWithBackupSchedule(): List<ServerView> = transaction {
        Server.find { Servers.backupSchedule.isNotNull() }.map { it.toServerView() }
    }

    override fun listExpiredRunning(now: kotlinx.datetime.LocalDateTime): List<ServerView> = transaction {
        Server.find {
            Servers.expiresAt.isNotNull() and
                (Servers.expiresAt lessEq now) and
                (Servers.status inList listOf("STARTING", "HEALTHY", "UNHEALTHY"))
        }.map { it.toServerView() }
    }

    override fun countByNetworkId(networkId: Uuid): Int = transaction {
        Server.find { Servers.networkId eq networkId }.count().toInt()
    }

    override fun countByNodeId(nodeId: Uuid): Int = transaction {
        Server.find { Servers.nodeId eq nodeId }.count().toInt()
    }
}
