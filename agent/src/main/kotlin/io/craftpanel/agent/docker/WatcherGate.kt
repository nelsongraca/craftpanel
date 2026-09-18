package io.craftpanel.agent.docker

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Gates which container deaths the [ContainerEventWatcher] reports to master as crashes.
 *
 * Two sets, both keyed by server id:
 * - managed  — servers owned by this agent; deaths are reportable unless stopping
 * - stopping — servers whose death is intentional (stop/remove/recreate in progress)
 *
 * A die event is reported iff the server is managed and NOT stopping. The cross-node
 * guard (agents sharing a Docker daemon) falls out of the managed set: another agent's
 * containers are never marked managed here.
 *
 * Ownership must be seeded from master intent ([markManaged], called on every desired-state
 * envelope), not only from [markStarted]. Otherwise an agent process restart empties the set
 * and every death of an already-running container is silently suppressed — see ADR 0005.
 */
class WatcherGate {

    private val log = LoggerFactory.getLogger(WatcherGate::class.java)

    private val managedServerIds = ConcurrentHashMap.newKeySet<String>()
    private val stoppingServerIds = ConcurrentHashMap.newKeySet<String>()

    /** Number of servers currently considered owned by this agent (observability/tests). */
    val managedCount: Int get() = managedServerIds.size

    /** Number of servers currently marked as intentionally stopping (observability/tests). */
    val stoppingCount: Int get() = stoppingServerIds.size

    /** Returns true iff the agent owns this server and the death was NOT intentional. */
    fun shouldReportDie(serverId: String): Boolean = managedServerIds.contains(serverId) && !stoppingServerIds.contains(serverId)

    /**
     * Marks a server as owned by this agent without asserting anything about its run state.
     * Called whenever master pushes intent for the server, so ownership survives an agent
     * process restart even if the agent never starts the (already-running) container itself.
     */
    fun markManaged(serverId: String) {
        if (managedServerIds.add(serverId)) {
            log.info("WatcherGate: server {} marked managed (managed={})", serverId, managedServerIds.size)
        }
    }

    /** Clears the stopping flag — the server is expected to be up again (or was never stopped). */
    fun clearStopping(serverId: String) {
        if (stoppingServerIds.remove(serverId)) {
            log.info("WatcherGate: server {} cleared stopping (stopping={})", serverId, stoppingServerIds.size)
        }
    }

    /** Call before stop/remove. Death is expected; watcher will suppress. */
    fun markStopping(serverId: String) {
        if (stoppingServerIds.add(serverId)) {
            log.info("WatcherGate: server {} marked stopping (stopping={})", serverId, stoppingServerIds.size)
        }
    }

    /**
     * Call on successful start/restart. Clears the stopping flag (any lingering die event from
     * the prior removal has already been delivered by the time we reach here). Also marks managed
     * so future unexpected deaths are reported.
     */
    fun markStarted(serverId: String) {
        markManaged(serverId)
        clearStopping(serverId)
    }

    /** Call on successful remove (server is gone, no future deaths expected). */
    fun markRemoved(serverId: String) {
        val wasManaged = managedServerIds.remove(serverId)
        val wasStopping = stoppingServerIds.remove(serverId)
        if (wasManaged || wasStopping) {
            log.info(
                "WatcherGate: server {} removed (managed={}, stopping={})",
                serverId,
                managedServerIds.size,
                stoppingServerIds.size
            )
        }
    }
}
