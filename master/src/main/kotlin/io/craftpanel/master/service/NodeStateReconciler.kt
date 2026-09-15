package io.craftpanel.master.service

import io.craftpanel.master.database.entity.*
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.domain.NodeHealth
import io.craftpanel.master.service.repo.*
import io.craftpanel.proto.NodeStateSnapshot
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.uuid.Uuid

class NodeStateReconciler(
    private val nodeRepository: NodeRepository,
) {

    private val log = LoggerFactory.getLogger(NodeStateReconciler::class.java)

    fun reconcileNodeState(nodeId: String, snapshot: NodeStateSnapshot): NodeHealth? {
        val kotlinNodeId = runCatching { Uuid.parse(nodeId) }.getOrNull() ?: return null
        val now = Clock.System.now()
        var resultHealth: NodeHealth? = null

        val currentStatus = nodeRepository.findById(kotlinNodeId)?.status

        log.debug("Node $nodeId: reconcileNodeState — currentStatus=$currentStatus, containers=${snapshot.containersCount}")

        if (currentStatus == "ACTIVE") {
            val newHealth = if (snapshot.routerRunning) NodeHealth.HEALTHY else NodeHealth.DEGRADED
            transaction {
                Node.findById(kotlinNodeId)
                    ?.let {
                        it.health = newHealth.name
                        it.lastSeenAt = now.toLocalDateTime(TimeZone.UTC)
                        it.swarmActive = snapshot.swarmActive
                    }
            }
            resultHealth = newHealth
            log.debug("Node {}: reconciled health={} (routerRunning={})", nodeId, newHealth, snapshot.routerRunning)
        }
        else {
            log.debug("Node $nodeId: status=$currentStatus — only updating lastSeenAt")
            transaction {
                Node.findById(kotlinNodeId)
                    ?.let { it.lastSeenAt = now.toLocalDateTime(TimeZone.UTC) }
            }
        }

        return resultHealth
    }

    fun markNodeUnreachable(nodeId: String) {
        val kotlinNodeId = runCatching { Uuid.parse(nodeId) }.getOrElse {
            log.warn("markNodeUnreachable: invalid nodeId format: $nodeId")
            return
        }
        val now = Clock.System.now()

        val node = nodeRepository.findById(kotlinNodeId) ?: return
        if (node.status != "ACTIVE") {
            log.debug("markNodeUnreachable: node $nodeId is not ACTIVE — skipping")
            return
        }

        transaction {
            Node.findById(kotlinNodeId)
                ?.let { it.health = "UNREACHABLE"; it.lastSeenAt = now.toLocalDateTime(TimeZone.UTC) }
        }
        transaction {
            ServerMigration.find {
                ((ServerMigrations.sourceNodeId eq kotlinNodeId) or (ServerMigrations.targetNodeId eq kotlinNodeId)) and
                    (ServerMigrations.status inList listOf("PENDING", "SYNCING", "CUTTING_OVER"))
            }
                .forEach {
                    it.status = "FAILED"
                    it.completedAt = now.toLocalDateTime(TimeZone.UTC)
                }
        }
        transaction {
            Backup.find { (Backups.nodeId eq kotlinNodeId) and (Backups.status eq "IN_PROGRESS") }
                .forEach {
                    it.status = "FAILED"
                    it.errorMessage = "Node went offline during backup"
                    it.completedAt = now.toLocalDateTime(TimeZone.UTC)
                }
        }

        log.warn("Node $nodeId marked UNREACHABLE: migrations → FAILED, backups → FAILED")
    }

    fun updateNodeHealth(nodeId: String, health: NodeHealth) {
        val kotlinNodeId = runCatching { Uuid.parse(nodeId) }.getOrElse {
            log.warn("updateNodeHealth: invalid nodeId format: $nodeId")
            return
        }
        transaction {
            Node.findById(kotlinNodeId)
                ?.let { it.health = health.name }
        }
    }

    fun updateNodeLastSeen(nodeId: String) {
        val kotlinNodeId = runCatching { Uuid.parse(nodeId) }.getOrElse {
            log.warn("updateNodeLastSeen: invalid nodeId format: $nodeId")
            return
        }
        transaction {
            Node.findById(kotlinNodeId)
                ?.let {
                    it.lastSeenAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.UTC)
                }
        }
    }
}
