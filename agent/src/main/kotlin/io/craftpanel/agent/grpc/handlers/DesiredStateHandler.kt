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
        // Keep the data-dir override registry in step with the spec before convergence so path
        // resolution (canonical root, symlink) matches the bind mount the operator will use.
        if (env.hasSpec()) ServerDataDirs.put(env.serverId, env.spec.dataDirName)
        loop.applyDesired(env)
    }

    fun handleServerRemoved(serverId: String) {
        // After ContainerHandler.handleRemove has deleted the data directory using the registry.
        ServerDataDirs.remove(serverId)
        loop.onServerRemoved(serverId)
    }
}
