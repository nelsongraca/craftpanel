package io.craftpanel.agent.docker

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
 */
class WatcherGate {

    // ponytail: two concurrent sets gate watcher; managed=owned-by-this-agent, stopping=intentionally-dying
    private val managedServerIds = ConcurrentHashMap.newKeySet<String>()
    private val stoppingServerIds = ConcurrentHashMap.newKeySet<String>()

    /** Returns true iff the agent owns this server and the death was NOT intentional. */
    fun shouldReportDie(serverId: String): Boolean =
        managedServerIds.contains(serverId) && !stoppingServerIds.contains(serverId)

    /** Call before stop/remove. Death is expected; watcher will suppress. */
    fun markStopping(serverId: String) = stoppingServerIds.add(serverId)

    /**
     * Call on successful start/restart. Clears the stopping flag (any lingering die event from
     * the prior removal has already been delivered by the time we reach here). Also marks managed
     * so future unexpected deaths are reported.
     */
    fun markStarted(serverId: String) {
        managedServerIds.add(serverId)
        stoppingServerIds.remove(serverId)
    }

    /** Call on successful remove (server is gone, no future deaths expected). */
    fun markRemoved(serverId: String) {
        managedServerIds.remove(serverId)
        stoppingServerIds.remove(serverId)
    }
}
