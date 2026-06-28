package io.craftpanel.master.service

import io.craftpanel.master.database.schema.Backups
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.ServerMigrations
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.NodeHealth
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.proto.ContainerState
import io.craftpanel.proto.NodeStateSnapshot
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.uuid.Uuid

class NodeStateReconciler {

    private val log = LoggerFactory.getLogger(NodeStateReconciler::class.java)

    fun reconcileNodeState(nodeId: String, snapshot: NodeStateSnapshot): NodeHealth? {
        val kotlinNodeId = runCatching { Uuid.parse(nodeId) }.getOrNull() ?: return null
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)
        var resultHealth: NodeHealth? = null

        transaction {
            val currentStatus = Nodes.selectAll()
                .where { Nodes.id eq kotlinNodeId }
                .firstOrNull()
                ?.get(Nodes.status)

            log.debug("Node $nodeId: reconcileNodeState — currentStatus=$currentStatus, containers=${snapshot.containersCount}")
            val byServerId = snapshot.containersList.associateBy { it.serverId }

            Servers.selectAll()
                .where { Servers.nodeId eq kotlinNodeId }
                .forEach { server ->
                    val serverId = server[Servers.id]
                    val dbStatus = ServerStatus.fromDb(server[Servers.status])
                    val container = byServerId[serverId.toString()]

                    val newStatus: ServerStatus? = if (container == null) {
                        mapMissingContainer(dbStatus)
                    }
                    else {
                        mapContainerState(container.runState, dbStatus)
                    }

                    if (newStatus != null) {
                        log.info("Node $nodeId reconcile: server $serverId $dbStatus → $newStatus")
                        Servers.update({ Servers.id eq serverId }) {
                            it[Servers.status] = newStatus.toDb()
                            it[Servers.lastSeenAt] = now
                        }
                    }
                }

            if (currentStatus == "ACTIVE") {
                val newHealth = if (snapshot.routerRunning) NodeHealth.HEALTHY else NodeHealth.DEGRADED
                Nodes.update({ Nodes.id eq kotlinNodeId }) {
                    it[Nodes.health] = newHealth.name
                    it[Nodes.lastSeenAt] = now
                    it[Nodes.swarmActive] = snapshot.swarmActive
                }
                resultHealth = newHealth
                log.debug("Node {}: reconciled health={} (routerRunning={})", nodeId, newHealth, snapshot.routerRunning)
            }
            else {
                log.debug("Node $nodeId: status=$currentStatus — only updating lastSeenAt")
                Nodes.update({ Nodes.id eq kotlinNodeId }) { it[Nodes.lastSeenAt] = now }
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
            .toLocalDateTime(TimeZone.UTC)

        transaction {
            val updated = Nodes.update({
                (Nodes.id eq kotlinNodeId) and (Nodes.status eq "ACTIVE")
            }) { it[Nodes.health] = "UNREACHABLE" }

            if (updated == 0) {
                log.debug("markNodeUnreachable: node $nodeId is not ACTIVE — skipping")
                return@transaction
            }

            val migrationCount = ServerMigrations.update({
                ((ServerMigrations.sourceNodeId eq kotlinNodeId) or (ServerMigrations.targetNodeId eq kotlinNodeId)) and
                        (ServerMigrations.status inList listOf("PENDING", "SYNCING", "CUTTING_OVER"))
            }) {
                it[ServerMigrations.status] = "FAILED"
                it[ServerMigrations.completedAt] = now
            }

            val backupCount = Backups.update({
                (Backups.nodeId eq kotlinNodeId) and (Backups.status eq "IN_PROGRESS")
            }) {
                it[Backups.status] = "FAILED"
                it[Backups.errorMessage] = "Node went offline during backup"
                it[Backups.completedAt] = now
            }

            log.warn("Node $nodeId marked UNREACHABLE: $migrationCount migrations → FAILED, $backupCount backups → FAILED")
        }
    }

    fun updateNodeHealth(nodeId: String, health: NodeHealth) {
        val kotlinNodeId = runCatching { Uuid.parse(nodeId) }.getOrElse {
            log.warn("updateNodeHealth: invalid nodeId format: $nodeId")
            return
        }
        transaction {
            Nodes.update({ (Nodes.id eq kotlinNodeId) and (Nodes.status eq "ACTIVE") }) {
                it[Nodes.health] = health.name
            }
        }
    }
}

fun mapContainerState(runState: ContainerState.RunState, dbStatus: ServerStatus): ServerStatus? = when {
    runState == ContainerState.RunState.RUNNING && dbStatus != ServerStatus.HEALTHY  -> ServerStatus.HEALTHY
    runState == ContainerState.RunState.STOPPED && dbStatus.isRunning                -> ServerStatus.STOPPED
    runState == ContainerState.RunState.EXITED && dbStatus != ServerStatus.UNHEALTHY -> ServerStatus.UNHEALTHY
    else                                                                             -> null
}

fun mapMissingContainer(dbStatus: ServerStatus): ServerStatus? =
    if (dbStatus != ServerStatus.STOPPED) ServerStatus.STOPPED else null
