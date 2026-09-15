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

    // In-flight converge per server. Lets a force-stop preempt a graceful stop that is holding
    // the per-server mutex (the mutex alone would serialize the SIGKILL behind the wait).
    private val inFlight = ConcurrentHashMap<String, Job>()

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
        if (env.desired == ServerDesiredState.Desired.STOPPED && env.force) {
            preemptWithKill(env.serverId)
        }
        return trigger(env.serverId)
    }

    /**
     * A force-stop must take effect immediately even while a graceful stop is running. The
     * graceful converge holds the per-server mutex for up to the stop timeout, so waiting behind
     * it defeats the point of `force`. SIGKILL the container out-of-band; the in-flight graceful
     * stop then completes and the mutated converge reconciles to STOPPED.
     */
    private fun preemptWithKill(serverId: String) {
        if (inFlight[serverId]?.isActive != true) return
        val containerName = store.get(serverId).spec?.containerName ?: "$containerNamePrefix-$serverId"
        log.info("Force stop for $serverId — preempting in-flight convergence with SIGKILL")
        scope.launch {
            runCatching { operator.forceKill(containerName) }
                .onFailure { log.warn("Preemptive force kill for $serverId failed", it) }
        }
    }

    /** Called by the ContainerEventWatcher on an unexpected death (gate already suppressed authored ones). */
    fun onContainerDie(serverId: String, exitCode: Int): Job {
        log.info("Unexpected container die event for server {} (exit {})", serverId, exitCode)
        return trigger(serverId)
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
        val job = scope.launch {
            val lock = locks.computeIfAbsent(serverId) { Mutex() }
            lock.withLock {
                runCatching { block() }
                    .onFailure { log.error("Convergence failed for server $serverId", it) }
            }
        }
        inFlight[serverId] = job
        job.invokeOnCompletion { inFlight.remove(serverId, job) }
        return job
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
        // Graceful stop must use the stop command carried in the stored spec (text stdin or a
        // signal sentinel like "^C"/"SIGTERM"). Dropping it degrades every envelope-driven stop
        // to a bare Docker SIGTERM.
        val stopCommand = state.spec?.stopCommand ?: ""
        when (val decision = result.decision) {
            is ConvergenceDecision.EnsureRunning -> executeEnsureRunning(serverId, decision.recreate)
            is ConvergenceDecision.ConditionalRestart -> executeConditionalRestart(serverId, decision.recreate, stopCommand = stopCommand)
            is ConvergenceDecision.EnsureStopped -> {
                if (actual.running) executeEnsureStopped(serverId, containerName, stopCommand = stopCommand)
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

    private suspend fun executeEnsureRunning(serverId: String, recreate: Boolean) {
        val state = store.get(serverId)
        val spec = state.spec
        if (spec == null) {
            log.warn("Cannot ensure RUNNING for server $serverId — no spec stored")
            return
        }
        out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.STARTING)
        try {
            operator.ensureRunning(spec, recreate)
            markHealthy(serverId, spec)
            out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.HEALTHY)
        } catch (e: Exception) {
            log.error("Failed to ensure RUNNING for server $serverId", e)
            out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY)
        }
    }

    private suspend fun executeConditionalRestart(
        serverId: String,
        recreate: Boolean,
        timeoutSeconds: Int = DEFAULT_STOP_TIMEOUT,
        stopCommand: String = "",
    ) {
        val state = store.get(serverId)
        val containerName = state.spec?.containerName ?: "$containerNamePrefix-$serverId"
        out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.STARTING)
        try {
            operator.ensureStopped(containerName, timeoutSeconds, stopCommand)
            executeEnsureRunning(serverId, recreate)
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

    /** A successful start means the crash-restart budget resets. */
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