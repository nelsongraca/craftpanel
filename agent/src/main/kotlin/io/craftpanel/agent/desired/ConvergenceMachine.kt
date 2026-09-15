package io.craftpanel.agent.desired

import io.craftpanel.proto.ServerDesiredState

/** Snapshot of the container's actual on-node state, gathered by the loop's operator layer. */
data class ActualState(
    val containerPresent: Boolean,
    val running: Boolean,
)

/** What the agent must do to converge [DesiredState] towards [ActualState]. */
sealed interface ConvergenceDecision {
    /** Already converged — nothing to do. Loop re-affirms the status that reflects reality. */
    data object NoOp : ConvergenceDecision

    /** Create the container if absent (or remove + recreate if spec differs), then start it. */
    data object EnsureRunning : ConvergenceDecision

    /** Graceful stop (`docker stop` with the stop command), then STARTING→no-op. */
    data object EnsureStopped : ConvergenceDecision

    /** Immediate SIGKILL (skip the graceful step) — `force=true`. */
    data object ForceKill : ConvergenceDecision

    /** User-initiated restart (force_restart): stop-if-running, then start. Never budget-capped. */
    data object ConditionalRestart : ConvergenceDecision

    /** Desired=RUNNING, container is not running, and the crash-restart budget is exhausted. */
    data class CrashLooped(val reason: String) : ConvergenceDecision
}

data class ConvergenceResult(
    val decision: ConvergenceDecision,
    /** The [DesiredState] after this decision — one-shot flags cleared, counts advanced. */
    val next: DesiredState,
)

/**
 * Pure next-state function for desired-state convergence. No Docker, no I/O, no clock —
 * [nowMillis] is an explicit input so every output follows deterministically from inputs
 * (trivially table-testable). Mirrors the master `ServerRestartManager` budget semantics:
 *
 * - A crash-restart is allowed while `count <= max_attempts`; the count resets when the
 *   window lapses or when the server reaches HEALTHY.
 * - `max_attempts <= 0` means "never auto-restart" — immediate [ConvergenceDecision.CrashLooped].
 * - Provisioning (container absent, desired=RUNNING) is never budget-capped.
 */
object ConvergenceMachine {

    fun decide(state: DesiredState, actual: ActualState, nowMillis: Long = System.currentTimeMillis()): ConvergenceResult {
        if (state.desired == ServerDesiredState.Desired.DESIRED_UNSPECIFIED) {
            return ConvergenceResult(ConvergenceDecision.NoOp, state)
        }

        return when (state.desired) {
            ServerDesiredState.Desired.STOPPED -> decideStopped(state, actual)
            ServerDesiredState.Desired.RUNNING -> decideRunning(state, actual, nowMillis)
            else -> ConvergenceResult(ConvergenceDecision.NoOp, state)
        }
    }

        private fun decideStopped(state: DesiredState, actual: ActualState): ConvergenceResult {
        if (!actual.running) {
            // Already stopped (or never created) — converged. One-shot `force` is still cleared.
            return ConvergenceResult(ConvergenceDecision.NoOp, state.clearOneShots())
        }
        return if (state.force) {
            ConvergenceResult(ConvergenceDecision.ForceKill, state.clearOneShots())
        } else {
            ConvergenceResult(ConvergenceDecision.EnsureStopped, state.clearOneShots())
        }
    }

    private fun decideRunning(state: DesiredState, actual: ActualState, nowMillis: Long): ConvergenceResult {
        // Already running. A user-initiated restart (force_restart) still fires; everything else
        // is a no-op — a spec change while running is reconfigure-only, applied at next start.
        if (actual.running) {
            return if (state.forceRestart) {
                ConvergenceResult(ConvergenceDecision.ConditionalRestart, state.clearOneShots())
            } else {
                ConvergenceResult(ConvergenceDecision.NoOp, state)
            }
        }

        // Not running.
        if (state.forceRestart) {
            // force_restart is only meaningful when we actually run; a stopped server with the
            // flag pending will consume the flag as an ordinary start.
            return ConvergenceResult(ConvergenceDecision.EnsureRunning, state.clearOneShots())
        }

        // Provisioning: container was never created — first start, never budget-capped.
        if (!actual.containerPresent) {
            return ConvergenceResult(ConvergenceDecision.EnsureRunning, state)
        }

        // Crash-restart path: the container exists but is not running.
        if (state.noRestart) {
            return ConvergenceResult(ConvergenceDecision.NoOp, state)
        }

        val budget = state.budget
        val allowed = budget?.let { b -> b.maxAttempts > 0 } ?: true
        if (!allowed) {
            return ConvergenceResult(
                ConvergenceDecision.CrashLooped("restart budget max_attempts=${budget?.maxAttempts} prohibits auto-restart"),
                state
            )
        }

        val candidateCount = restartCandidateCount(state, nowMillis)
        if (candidateCount > (budget?.maxAttempts ?: Int.MAX_VALUE)) {
            return ConvergenceResult(
                ConvergenceDecision.CrashLooped(
                    "crash-restart budget exhausted (${state.restartCount}/" +
                        "${budget?.maxAttempts} within ${budget?.windowSeconds}s)"
                ),
                state
            )
        }

        val next = state.copy(
            restartCount = candidateCount,
            windowStartEpochMillis = state.windowStartEpochMillis ?: nowMillis,
            forceRestart = false,
            force = false,
        )
        return ConvergenceResult(ConvergenceDecision.EnsureRunning, next)
    }

    private fun restartCandidateCount(state: DesiredState, nowMillis: Long): Int {
        val windowStart = state.windowStartEpochMillis
        val windowSeconds = state.budget?.windowSeconds ?: Long.MAX_VALUE
        return if (windowStart != null && nowMillis - windowStart > windowSeconds * 1000) {
            // Window lapsed — reset to a fresh single attempt (matches ServerRestartManager).
            1
        } else {
            state.restartCount + 1
        }
    }

    private fun DesiredState.clearOneShots(): DesiredState = copy(forceRestart = false, force = false)
}