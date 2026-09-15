package io.craftpanel.agent.grpc.handlers

import io.craftpanel.agent.desired.ConvergenceLoop
import io.craftpanel.proto.RestartContainerCommand
import io.craftpanel.proto.ServerDesiredState
import io.craftpanel.proto.StartContainerCommand
import io.craftpanel.proto.StopContainerCommand

/**
 * Routes all container-lifecycle intent into the [ConvergenceLoop]. Handles both:
 * - The new `ServerDesiredState` envelope (master declarative model).
 * - Legacy one-shot payloads (`StartContainerCommand`, `StopContainerCommand`,
 *   `RestartContainerCommand`) during the transition — the loop translates them into
 *   desired-state mutations and executes the op directly, so a subsequent crash
 *   still converges correctly.
 *
 * [handleRemove] is intentionally NOT here — it remains in [ContainerHandler] because
 * permanent deletion is not an idempotent convergence (no desired-state; the server is gone).
 */
class DesiredStateHandler(private val loop: ConvergenceLoop) {

    fun handleDesiredState(env: ServerDesiredState) {
        loop.applyDesired(env)
    }

    fun handleStartCommand(cmd: StartContainerCommand) {
        loop.applyLegacyStart(cmd)
    }

    fun handleStopCommand(serverId: String, containerName: String, cmd: StopContainerCommand) {
        loop.applyLegacyStop(serverId, containerName, cmd.timeoutSeconds, cmd.stopCommand, cmd.force)
    }

    fun handleRestartCommand(serverId: String, containerName: String, cmd: RestartContainerCommand) {
        loop.applyLegacyRestart(serverId, containerName, cmd.timeoutSeconds, cmd.stopCommand)
    }

    fun handleServerRemoved(serverId: String) {
        loop.onServerRemoved(serverId)
    }
}