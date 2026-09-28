package io.craftpanel.master.service

import java.util.concurrent.ConcurrentHashMap

/** mc-router liveness + "newer image pre-pulled" state reported by an agent. */
data class RouterStatus(val running: Boolean, val updateAvailable: Boolean)

/**
 * In-memory per-node mc-router state. Not persisted: the agent re-reports it on every (re)connect as
 * part of the node-state snapshot and on every change, and a stale entry is cleared on disconnect.
 */
class RouterStatusStore {

    private val statuses = ConcurrentHashMap<String, RouterStatus>()

    fun update(nodeId: String, status: RouterStatus) {
        statuses[nodeId] = status
    }

    fun get(nodeId: String): RouterStatus? = statuses[nodeId]

    fun clear(nodeId: String) {
        statuses.remove(nodeId)
    }
}
