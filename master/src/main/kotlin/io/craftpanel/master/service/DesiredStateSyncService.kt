package io.craftpanel.master.service

import io.craftpanel.master.domain.DesiredStatus
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.ServerView
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

/**
 * Re-pushes `ServerDesiredState` envelopes for every server whose `desired_status` is set.
 * Used at boot time (first-time push to already-connected agents) and after every node reconnect
 * (so the agent re-acquires master's intent without waiting for a user action).
 *
 * A row with no recorded intent but an agent-reported running status has its intent re-derived
 * (`RUNNING`) and persisted before the push — otherwise such rows are skipped silently and the
 * agent never converges (a stopped container then stays down until a human intervenes).
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
            val desired = resolveDesiredStatus(server) ?: continue
            val ok = lifecycle.sendDesiredState(server, desired)
            if (ok) {
                pushed++
                log.info("pushAll: pushed desired={} for server {}", desired, server.id)
            } else {
                log.warn("pushAll: agent not connected for server {} (node {})", server.id, server.nodeId)
            }
        }
        if (pushed > 0) log.info("pushAll: pushed {} desired-state envelopes", pushed)
    }

    /**
     * Master's intent for a server: the recorded value, else one re-derived from the agent-reported
     * status and persisted. A reported status other than `STOPPED` (HEALTHY, STARTING, UNHEALTHY,
     * CRASH_LOOPED) implies there was once a RUNNING intent, so RUNNING is re-derived and persisted
     * — otherwise the row is skipped and the agent never converges. A `STOPPED` or unrecognised
     * report leaves intent unset (nothing to recover).
     */
    private fun resolveDesiredStatus(server: ServerView): DesiredStatus? {
        DesiredStatus.fromDb(server.desiredStatus)?.let { return it }
        val reported = runCatching { ServerStatus.fromDb(server.status) }.getOrNull()
        if (reported == null) {
            log.warn("pushAll: server {} has no desired_status and unrecognised status '{}' — skipping", server.id, server.status)
            return null
        }
        if (reported == ServerStatus.STOPPED) {
            log.info("pushAll: server {} has no desired_status (reported=STOPPED) — skipping", server.id)
            return null
        }
        log.info("pushAll: server {} has no desired_status (reported={}) — deriving RUNNING", server.id, reported)
        lifecycle.persistDesiredStatus(server.id, DesiredStatus.RUNNING)
        return DesiredStatus.RUNNING
    }
}
