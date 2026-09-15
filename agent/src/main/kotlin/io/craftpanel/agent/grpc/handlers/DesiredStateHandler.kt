package io.craftpanel.agent.grpc.handlers

import io.craftpanel.agent.desired.ConvergenceLoop
import io.craftpanel.proto.ServerDesiredState

/**
 * Routes container-lifecycle intent into the [ConvergenceLoop].
 *
 * All lifecycle actions (start/stop/restart, spec edits) arrive as `ServerDesiredState` envelopes;
 * the legacy one-shot payloads were removed in the desired-state cut-over.
 *
 * [handleServerRemoved] is intentionally separate — permanent deletion is not an idempotent
 * convergence (no desired-state; the server is gone).
 */
class DesiredStateHandler(private val loop: ConvergenceLoop) {

    fun handleDesiredState(env: ServerDesiredState) {
        loop.applyDesired(env)
    }

    fun handleServerRemoved(serverId: String) {
        loop.onServerRemoved(serverId)
    }
}