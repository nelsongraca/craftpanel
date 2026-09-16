package io.craftpanel.master.service

import io.craftpanel.master.domain.DesiredStatus
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.ServerView
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

/**
 * Re-pushes `ServerDesiredState` envelopes for every server whose `desired_status` is set.
 * Used at boot time (first-time push to already-connected agents) and after every node reconnect
 * (so the agent re-acquires master's intent without waiting for a user action).
 */
class DesiredStateSyncService(private val lifecycle: ContainerLifecycle, private val serverRepository: ServerRepository) {
    private val log = LoggerFactory.getLogger(DesiredStateSyncService::class.java)

    /** Push desired-state envelopes for every server on [nodeId] that has a non-null [DesiredStatus]. */
    suspend fun pushAllForNode(nodeId: String) {
        val kotlinNodeId = Uuid.parse(nodeId)
        pushAll(serverRepository.listByNodeId(kotlinNodeId))
    }

    /** Push desired-state envelopes for all servers across every node. Called once at startup. */
    suspend fun pushAllOnBoot() {
        pushAll(serverRepository.listAll())
    }

    private suspend fun pushAll(servers: List<ServerView>) {
        var pushed = 0
        for (server in servers) {
            val desired = DesiredStatus.fromDb(server.desiredStatus) ?: continue
            val ok = lifecycle.sendDesiredState(server, desired)
            if (ok) pushed++ else log.warn("pushAll: agent not connected for server {} (node {})", server.id, server.nodeId)
        }
        if (pushed > 0) log.info("pushAll: pushed {} desired-state envelopes", pushed)
    }
}
