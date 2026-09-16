package io.craftpanel.agent.desired

import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.common.ContainerNames
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
 * Two entry points feed [converge]:
 * 1. A `ServerDesiredState` envelope from master (apply — store intent, then converge).
 * 2. An unexpected container death from the [io.craftpanel.agent.docker.ContainerEventWatcher]
 *    (only unauthored deaths reach here — the WatcherGate suppresses authored ones).
 *
 * All per-server work is serialized by a per-server [Mutex]; each converge re-reads fresh state and
 * re-decides, so stale/queued events converge to a harmless no-op once the target holds.
 */
class ConvergenceLoop(
    private val store: DesiredStateStore,
    private val operator: ContainerOperator,
    private val containerNamePrefix: String,
    private val out: AgentOutbound,
    private val scope: CoroutineScope
) {

    private val log = LoggerFactory.getLogger(ConvergenceLoop::class.java)
    private val names = ContainerNames(containerNamePrefix)
    private val locks = ConcurrentHashMap<String, Mutex>()

    // In-flight converge per server. Lets a force-stop preempt a graceful stop that is holding
    // the per-server mutex (the mutex alone would serialize the SIGKILL behind the wait).
    private val inFlight = ConcurrentHashMap<String, Job>()

    // ── Entry points ────────────────────────────────────────────────────────

    /** Applies a `ServerDesiredState` envelope: store the intent, then converge. */
    fun applyDesired(env: ServerDesiredState): Job {
        // Log the intent + the identity-carrying spec fields so "did the agent receive the new
        // config?" is answerable from logs. Deliberately NOT the whole env map (may hold secrets).
        if (env.hasSpec()) {
            log.info(
                "Desired state for {}: desired={} forceRestart={} force={} noRestart={} image={} TYPE={} VERSION={} container={}",
                env.serverId, env.desired, env.forceRestart, env.force, env.noRestart,
                env.spec.image, env.spec.envVarsMap["TYPE"] ?: "-", env.spec.envVarsMap["VERSION"] ?: "-",
                env.spec.containerName
            )
        } else {
            log.info(
                "Desired state for {}: desired={} forceRestart={} force={} noRestart={} (no spec)",
                env.serverId,
                env.desired,
                env.forceRestart,
                env.force,
                env.noRestart
            )
        }
        store.upsert(env.serverId) { state ->
            // A user-initiated (re)start — a fresh start from a non-RUNNING intent, or an explicit
            // restart — clears the crash-restart history so an operator can recover a crash-looped
            // server. Autonomous crash-restarts must NOT reset it, otherwise a container that
            // launches then immediately dies would restart forever and never report CRASH_LOOPED.
            val userStart = env.desired == ServerDesiredState.Desired.RUNNING &&
                (state.desired != ServerDesiredState.Desired.RUNNING || env.forceRestart)
            state.copy(
                desired = env.desired,
                spec = if (env.hasSpec()) env.spec else state.spec,
                budget = if (env.hasRestartBudget()) env.restartBudget else state.budget,
                forceRestart = env.forceRestart,
                force = env.force,
                noRestart = env.noRestart,
                restartCount = if (userStart) 0 else state.restartCount,
                windowStartEpochMillis = if (userStart) null else state.windowStartEpochMillis
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
        val containerName = store.get(serverId).spec?.containerName ?: names.container(serverId)
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

    private fun trigger(serverId: String): Job = launchConverge(serverId) { converge(serverId) }

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
        val containerName = state.spec?.containerName ?: names.container(serverId)
        val containerPresent = operator.containerExists(containerName)
        val running = containerPresent && operator.isRunning(containerName)
        // Inspect the live container so recreate-if-diff is based on its real configuration, not only
        // the in-memory applied spec (which is lost when the agent process restarts).
        val specMatches = if (containerPresent && state.spec != null) {
            operator.inspect(containerName)?.let { operator.matches(it, state.spec) }
        } else {
            null
        }
        if (specMatches == true && state.appliedSpec == null) {
            // Cache the confirmed spec so a later uninspectable converge still knows what is applied.
            store.upsert(serverId) { it.copy(appliedSpec = state.spec) }
        }
        val actual = ActualState(containerPresent = containerPresent, running = running, specMatches = specMatches)
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
            val created = operator.ensureRunning(spec, recreate)
            // Only a container that was actually created/recreated has this spec applied; a plain
            // start of an existing container must not claim it (that would hide a stale container
            // behind a matching appliedSpec and abort every future recreate).
            if (created) recordAppliedSpec(serverId, spec)
            out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.HEALTHY)
        } catch (e: Exception) {
            log.error("Failed to ensure RUNNING for server $serverId", e)
            out.tryServerStatus(serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY)
        }
    }

    private suspend fun executeConditionalRestart(serverId: String, recreate: Boolean, timeoutSeconds: Int = DEFAULT_STOP_TIMEOUT, stopCommand: String = "") {
        val state = store.get(serverId)
        val containerName = state.spec?.containerName ?: names.container(serverId)
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

    /**
     * Records the spec the container was actually created/recreated with (drives recreate-if-diff).
     * Called only when [ContainerOperator.ensureRunning] created a container — never on a plain
     * start of an existing one. It does NOT touch the crash-restart budget: a start that immediately
     * dies must keep accumulating so a crash loop is eventually reported as
     * [ConvergenceDecision.CrashLooped]. The budget is cleared by an explicit user (re)start
     * ([applyDesired]) or by the window lapsing in [ConvergenceMachine].
     */
    private fun recordAppliedSpec(serverId: String, appliedSpec: StartContainerCommand) {
        store.upsert(serverId) { state -> state.copy(appliedSpec = appliedSpec) }
    }

    companion object {
        private const val DEFAULT_STOP_TIMEOUT = 45
    }
}
