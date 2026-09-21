package io.craftpanel.agent.desired

import io.craftpanel.proto.RestartBudget
import io.craftpanel.proto.ServerDesiredState
import io.craftpanel.proto.StartContainerCommand
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-server desired state the agent converges towards. Process-scoped singleton:
 * survives agent reconnects (the store outlives the control-stream scope) and dies
 * with the process. Master re-pushes all envelopes on reconnect and on boot, so
 * nothing on disk is required to re-converge.
 */
data class DesiredState(
    val serverId: String,
    val desired: ServerDesiredState.Desired,
    /** Full runtime spec the agent converges to; null until the first push with a spec. */
    val spec: StartContainerCommand?,
    /** Restart budget; null if never sent — treated as unlimited (no cap). */
    val budget: RestartBudget?,
    /** Spec the current container was created/started with — drives the recreate-if-diff decision. */
    val appliedSpec: StartContainerCommand?,
    /** Consecutive crash-restarts within the current window. */
    val restartCount: Int,
    /** Epoch millis of the current budget window; null before the first restart. */
    val windowStartEpochMillis: Long?,
    /** Run-once: stop-if-running then start. Cleared after application. */
    val forceRestart: Boolean,
    /** Run-once: SIGKILL. Cleared after application. */
    val force: Boolean,
    /** Sticky: suppress crash-restart while desired=RUNNING. */
    val noRestart: Boolean,
) {
    companion object {
        fun unset(serverId: String) = DesiredState(
            serverId = serverId,
            desired = ServerDesiredState.Desired.DESIRED_UNSPECIFIED,
            spec = null,
            budget = null,
            appliedSpec = null,
            restartCount = 0,
            windowStartEpochMillis = null,
            forceRestart = false,
            force = false,
            noRestart = false,
        )
    }
}

/**
 * Thread-safe in-memory store of per-server desired state. Reads/writes are atomic per key; callers
 * that must not observe a half-applied envelope serialize through [ConvergenceLoop]'s per-server
 * lock, which is held around every read-modify-write cycle.
 */
class DesiredStateStore {

    private val states = ConcurrentHashMap<String, DesiredState>()

    /** Applies [mutate] atomically per server; returns the resulting state. */
    fun upsert(serverId: String, mutate: (DesiredState) -> DesiredState): DesiredState {
        val updated = mutate(states.getOrDefault(serverId, DesiredState.unset(serverId)))
        states[serverId] = updated
        return updated
    }

    fun get(serverId: String): DesiredState = states[serverId] ?: DesiredState.unset(serverId)

    fun all(): List<DesiredState> = states.values.toList()

    /** Removes the server from the store (permanent server deletion). */
    fun remove(serverId: String) {
        states.remove(serverId)
    }
}