package io.craftpanel.agent.desired

import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.proto.RestartBudget
import io.craftpanel.proto.ServerDesiredState
import io.craftpanel.proto.ServerStatusUpdate
import io.craftpanel.proto.StartContainerCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates desired-state convergence for the container lifecycle, per connection.
 *
 * Three entry points feed [converge]:
 * 1. A `ServerDesiredState` envelope from master (apply — store intent, then converge).
 * 2. An unexpected container death from the [io.craftpanel.agent.docker.ContainerEventWatcher]
 *    (only unauthored deaths reach here — the WatcherGate suppresses authored ones).
 * 3. Legacy one-shot commands (start/stop/restart) during the master transition — they store the
 *    intent and run the op directly, so a subsequent crash still converges correctly.
 *
 * All per-server work is serialized by a per-server [Mutex]; each converge re-reads fresh state and
 * re-decides, so stale/queued events converge to a harmless no-op once the target holds.
 */
class ConvergenceLoop(
    private val store: DesiredStateStore,
    private val operator: ContainerOperator,
    private val containerNamePrefix: String,
    private val out: AgentOutbound,
    private val scope: CoroutineScope,
) {

    private val log = LoggerFactory.getLogger(ConvergenceLoop::class.java)
    private val locks = ConcurrentHashMap<String, Mutex>()

    // ── Entry points ────────────────────────────────────────────────────────

    /** Applies a `ServerDesiredState` envelope: store the intent, then converge. */
    fun applyDesired(env: ServerDesiredState): Job {
        store.upsert(env.serverId) { state ->
            state.copy(
                desired = env.desired,
                spec = if (env.hasSpec()) env.spec else state.spec,
                budget = if (env.hasRestartBudget()) env.restartBudget else state.budget,
                forceRestart = env.forceRestart,
                force = env.force,
                noRestart = env.noRestart,
            )
        }
        return trigger(env.serverId)
    }

    /** Called by the ContainerEventWatcher on an unexpected death (gate already suppressed authored ones). */
    fun onContainerDie(serverId: String, exitCode: Int): Job {
        log.info("Unexpected container die event for server {} (exit {})", serverId, exitCode)
        return trigger(serverId)
    }

    /**
     * Legacy start command (master pre-desired-state): store RUNNING intent + spec, execute the
     * provisioning start directly (explicit params honored), report the outcome.
     */
    fun applyLegacyStart(cmd: StartContainerCommand): Job {
        store.upsert(cmd.serverId) { state ->
            state.copy(
                desired = ServerDesiredState.Desired.RUNNING,
                spec = cmd,
                forceRestart = false,
                force = false,
            )
        }
        return launchConverge(cmd.serverId) { executeEnsureRunning(cmd.serverId) }
    }

    /** Legacy stop command: store STOPPED intent (+ force), execute the stop directly. */
    fun applyLegacyStop(serverId: String, containerName: String, timeoutSeconds: Int, stopCommand: String, force: Boolean): Job {
        store.upsert(serverId) { state ->
            state.copy(
                desired = ServerDesiredState.Desired.STOPPED,
                force = force,
                forceRestart = false,
            )
        }
        return launchConverge(serverId) {
            if (force) {
                executeForceKill(serverId, containerName)
            } else {
                executeEnsureStopped(serverId, containerName, timeoutSeconds, stopCommand)
            }
        }
    }

    /** Legacy restart command: store RUNNING intent + force_restart, stop then start directly. */
    fun applyLegacyRestart(serverId: String, containerName: String, timeoutSeconds: Int, stopCommand: String): Job {
        store.upsert(serverId) { state ->
            state.copy(
                desired = ServerDesiredState.Desired.RUNNING,
                forceRestart = true,
                force = false,
            )
        }
        return launchConverge(serverId) { executeConditionalRestart(serverId, timeoutSeconds, stopCommand) }
    }

    /**
     * Removes the server from the desired-state store (permanent deletion). Called by the
     * ContainerHandler.remove path so the store does not retain intent for deleted servers.
     */
    fun onServerRemoved(serverId: String) {
        store.remove(serverId)
    }

    // ── Convergence ─────────────────────────────────────────────────────────

    private fun trigger(serverId: String): Job {
        return launchConverge(serverId) { converge(serverId) }
    }

    private fun launchConverge(serverId: String, block: suspend () -> Unit): Job {
        return scope.launch {
            val lock = locks.computeIfAbsent(serverId) { Mutex() }
            lock.withLock {
                runCatching { block() }
                    .onFailure { log.error("Convergence failed for server $serverId", it) }
            }
        }
    }

    private suspend fun converge(serverId: String) {
        val state = store.get(serverId)
        if (state.desired == ServerDesiredState.Desired.DESIRED_UNSPECIFIED) return
        val containerName = state.spec?.containerName ?: "$containerNamePrefix-$serverId"
        val actual = ActualState(
            containerPresent = operator.containerExists(containerName),
            running = operator.isRunning(containerName),
        )
        val result = ConvergenceMachine.decide(state, actual)
        store.upsert(serverId) { result.next }
        when (val decision = result.decision) {
            is ConvergenceDecision.EnsureRunning -> executeEnsureRunning(serverId)
            is ConvergenceDecision.ConditionalRestart -> executeConditionalRestart(serverId)
            is ConvergenceDecision.EnsureStopped -> {
                if (actual.running) executeEnsureStopped(serverId, containerName)
            }
            is ConvergenceDecision.ForceKill -> executeForceKill(serverId, containerName)
            is ConvergenceDecision.CrashLooped -> {
                log.warn("Server {} crash-looped: {}", serverId, decision.reason)
                out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.CRASH_LOOPED)
            }
            is ConvergenceDecision.NoOp -> {
                val status = when (state.desired) {
                    ServerDesiredState.Desired.RUNNING if actual.running -> ServerStatusUpdate.ServerStatus.HEALTHY
                    ServerDesiredState.Desired.STOPPED if !actual.running -> ServerStatusUpdate.ServerStatus.STOPPED
                    else -> null
                }
                if (status != null) out.tryServerStatus(serverId, status)
            }
        }
    }

    private suspend fun executeEnsureRunning(serverId: String) {
        val state = store.get(serverId)
        val spec = state.spec
        if (spec == null) {
            log.warn("Cannot ensure RUNNING for server $serverId — no spec stored")
            return
        }
        out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.STARTING)
        try {
            operator.ensureRunning(spec)
            markHealthy(serverId, spec)
            out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.HEALTHY)
        } catch (e: Exception) {
            log.error("Failed to ensure RUNNING for server $serverId", e)
            out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY)
        }
    }

    private suspend fun executeConditionalRestart(serverId: String, timeoutSeconds: Int = DEFAULT_STOP_TIMEOUT, stopCommand: String = "") {
        val state = store.get(serverId)
        val containerName = state.spec?.containerName ?: "$containerNamePrefix-$serverId"
        out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.STARTING)
        try {
            operator.ensureStopped(containerName, timeoutSeconds, stopCommand)
            if (state.spec != null) {
                executeEnsureRunning(serverId)
            } else {
                // Legacy restart carries no spec — the container already exists, start by name.
                operator.ensureRunningByName(containerName)
                markHealthy(serverId, appliedSpec = io.craftpanel.proto.StartContainerCommand.getDefaultInstance())
                out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.HEALTHY)
            }
        } catch (e: Exception) {
            log.error("Failed to restart server $serverId", e)
            out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY)
        }
    }

    private suspend fun executeEnsureStopped(serverId: String, containerName: String, timeoutSeconds: Int = DEFAULT_STOP_TIMEOUT, stopCommand: String = "") {
        try {
            operator.ensureStopped(containerName, timeoutSeconds, stopCommand)
            out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.STOPPED)
        } catch (e: Exception) {
            log.error("Failed to stop server $serverId", e)
            out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY)
        }
    }

    private suspend fun executeForceKill(serverId: String, containerName: String) {
        try {
            operator.forceKill(containerName)
            out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.STOPPED)
        } catch (e: Exception) {
            log.error("Failed to force-kill server $serverId", e)
            out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY)
        }
    }

    /** A successful start means the crash-restart budget resets (mirrors ServerRestartManager.reset). */
    private fun markHealthy(serverId: String, appliedSpec: StartContainerCommand) {
        store.upsert(serverId) { state ->
            state.copy(
                appliedSpec = appliedSpec,
                restartCount = 0,
                windowStartEpochMillis = null,
            )
        }
    }

    companion object {
        private const val DEFAULT_STOP_TIMEOUT = 45
    }
}