package io.craftpanel.master.service

import io.craftpanel.master.domain.NodeHealth
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.BackupRepository
import io.craftpanel.master.service.repo.MigrationRepository
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.proto.ContainerState
import io.craftpanel.proto.NodeStateSnapshot
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.uuid.Uuid

class NodeStateReconciler(
    private val serverRepository: ServerRepository,
    private val nodeRepository: NodeRepository,
    private val migrationRepository: MigrationRepository,
    private val backupRepository: BackupRepository
) {

    private val log = LoggerFactory.getLogger(NodeStateReconciler::class.java)

    fun reconcileNodeState(nodeId: String, snapshot: NodeStateSnapshot): NodeHealth? {
        val kotlinNodeId = runCatching { Uuid.parse(nodeId) }.getOrNull() ?: return null
        val now = Clock.System.now()
        var resultHealth: NodeHealth? = null

        val currentStatus = nodeRepository.findById(kotlinNodeId)?.status

        log.debug("Node $nodeId: reconcileNodeState — currentStatus=$currentStatus, containers=${snapshot.containersCount}")
        val byServerId = snapshot.containersList.associateBy { it.serverId }

        serverRepository.listByNodeId(kotlinNodeId)
            .forEach { server ->
                val serverId = server.id
                val dbStatus = ServerStatus.fromDb(server.status)
                val container = byServerId[serverId.toString()]

                val newStatus: ServerStatus? = if (container == null) {
                    mapMissingContainer(dbStatus)
                } else {
                    mapContainerState(container.runState, dbStatus)
                }

                if (newStatus != null) {
                    log.info("Node $nodeId reconcile: server $serverId $dbStatus → $newStatus")
                    serverRepository.updateStatus(serverId, newStatus.toDb(), now)
                }
            }

        if (currentStatus == "ACTIVE") {
            val newHealth = if (snapshot.routerRunning) NodeHealth.HEALTHY else NodeHealth.DEGRADED
            nodeRepository.updateHealth(kotlinNodeId, newHealth.name)
            nodeRepository.updateLastSeen(kotlinNodeId, now, null, null)
            nodeRepository.updateSwarmActive(kotlinNodeId, snapshot.swarmActive)
            resultHealth = newHealth
            log.debug("Node {}: reconciled health={} (routerRunning={})", nodeId, newHealth, snapshot.routerRunning)
        } else {
            log.debug("Node $nodeId: status=$currentStatus — only updating lastSeenAt")
            nodeRepository.updateLastSeen(kotlinNodeId, now, null, null)
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

        nodeRepository.markUnreachable(kotlinNodeId, now)
        migrationRepository.failMigrationsForNode(kotlinNodeId)
        backupRepository.failBackupsForNode(kotlinNodeId)

        log.warn("Node $nodeId marked UNREACHABLE: migrations → FAILED, backups → FAILED")
    }

    fun updateNodeHealth(nodeId: String, health: NodeHealth) {
        val kotlinNodeId = runCatching { Uuid.parse(nodeId) }.getOrElse {
            log.warn("updateNodeHealth: invalid nodeId format: $nodeId")
            return
        }
        nodeRepository.updateHealth(kotlinNodeId, health.name)
    }
}

fun mapContainerState(runState: ContainerState.RunState, dbStatus: ServerStatus): ServerStatus? = when {
    runState == ContainerState.RunState.RUNNING && dbStatus != ServerStatus.HEALTHY -> ServerStatus.HEALTHY
    runState == ContainerState.RunState.STOPPED && dbStatus.isRunning -> ServerStatus.STOPPED
    runState == ContainerState.RunState.EXITED && dbStatus != ServerStatus.UNHEALTHY -> ServerStatus.UNHEALTHY
    else -> null
}

fun mapMissingContainer(dbStatus: ServerStatus): ServerStatus? = if (dbStatus != ServerStatus.STOPPED) ServerStatus.STOPPED else null
