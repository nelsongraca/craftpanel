package io.craftpanel.agent.desired

import io.craftpanel.proto.RestartBudget
import io.craftpanel.proto.ServerDesiredState
import io.craftpanel.proto.StartContainerCommand
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ConvergenceMachineTest :
    FunSpec({

        val now = 1_000_000_000_000L

        fun restartBudget(maxAttempts: Int = 3, windowSeconds: Long = 600) =
            RestartBudget.newBuilder()
                .setMaxAttempts(maxAttempts)
                .setWindowSeconds(windowSeconds)
                .build()

        val baseSpec = StartContainerCommand.getDefaultInstance()

        fun state(
            desired: ServerDesiredState.Desired = ServerDesiredState.Desired.RUNNING,
            budget: RestartBudget? = restartBudget(),
            restartCount: Int = 0,
            windowStartEpochMillis: Long? = null,
            forceRestart: Boolean = false,
            force: Boolean = false,
            noRestart: Boolean = false,
            spec: StartContainerCommand? = baseSpec,
            appliedSpec: StartContainerCommand? = null,
        ) = DesiredState(
            serverId = "srv-1",
            desired = desired,
            spec = spec,
            budget = budget,
            appliedSpec = appliedSpec,
            restartCount = restartCount,
            windowStartEpochMillis = windowStartEpochMillis,
            forceRestart = forceRestart,
            force = force,
            noRestart = noRestart,
        )

        val running = ActualState(containerPresent = true, running = true)
        val stopped = ActualState(containerPresent = true, running = false)
        val absent = ActualState(containerPresent = false, running = false)

        test("desired=UNSPECIFIED is always NoOp") {
            val result = ConvergenceMachine.decide(
                state(desired = ServerDesiredState.Desired.DESIRED_UNSPECIFIED),
                absent,
                now
            )
            result.decision shouldBe ConvergenceDecision.NoOp
        }

        test("desired=STOPPED already stopped clears one-shots and does nothing") {
            val result = ConvergenceMachine.decide(state(desired = ServerDesiredState.Desired.STOPPED, force = true), stopped, now)
            result.decision shouldBe ConvergenceDecision.NoOp
            result.next.force shouldBe false
        }

        test("desired=STOPPED running with force=false issues graceful EnsureStopped") {
            val result = ConvergenceMachine.decide(state(desired = ServerDesiredState.Desired.STOPPED), running, now)
            result.decision shouldBe ConvergenceDecision.EnsureStopped
        }

        test("desired=STOPPED running with force=true issues ForceKill") {
            val result = ConvergenceMachine.decide(state(desired = ServerDesiredState.Desired.STOPPED, force = true), running, now)
            result.decision shouldBe ConvergenceDecision.ForceKill
            result.next.force shouldBe false
        }

        test("desired=RUNNING and running is a NoOp (spec reconfig applies at next start)") {
            val result = ConvergenceMachine.decide(state(), running, now)
            result.decision shouldBe ConvergenceDecision.NoOp
        }

        test("desired=RUNNING and running with force_restart issues ConditionalRestart") {
            val result = ConvergenceMachine.decide(state(forceRestart = true, appliedSpec = baseSpec), running, now)
            result.decision shouldBe ConvergenceDecision.ConditionalRestart(false)
            result.next.forceRestart shouldBe false
        }

        test("desired=RUNNING, stopped, force_restart pending consumes as an ordinary start") {
            val result = ConvergenceMachine.decide(state(forceRestart = true, appliedSpec = baseSpec), stopped, now)
            result.decision shouldBe ConvergenceDecision.EnsureRunning(false)
            result.next.forceRestart shouldBe false
        }

        test("desired=RUNNING with container absent is provisioning — never budget-capped") {
            val exhausted = state(restartCount = 10, windowStartEpochMillis = now - 1)
            val result = ConvergenceMachine.decide(exhausted, absent, now)
            result.decision shouldBe ConvergenceDecision.EnsureRunning(false)
        }

        test("first crash within budget issues EnsureRunning and advances the counter") {
            val result = ConvergenceMachine.decide(state(), stopped, now)
            result.decision shouldBe ConvergenceDecision.EnsureRunning(false)
            result.next.restartCount shouldBe 1
            result.next.windowStartEpochMillis shouldBe now
        }

        test("crash count accumulates while the window is open") {
            val s = state(restartCount = 2, windowStartEpochMillis = now - 1)
            val result = ConvergenceMachine.decide(s, stopped, now)
            result.next.restartCount shouldBe 3
        }

        test("crash count resets to a fresh attempt when the window lapses") {
            val s = state(restartCount = 2, windowStartEpochMillis = now - (600 * 1000) - 1)
            val result = ConvergenceMachine.decide(s, stopped, now)
            result.next.restartCount shouldBe 1
        }

        test("exceeding max_attempts reports CrashLooped without advancing") {
            val s = state(restartCount = 3, windowStartEpochMillis = now - 1)
            val result = ConvergenceMachine.decide(s, stopped, now)
            (result.decision as ConvergenceDecision.CrashLooped).reason.isNotEmpty() shouldBe true
            result.next shouldBe s
        }

        test("max_attempts <= 0 means never auto-restart — immediate CrashLooped") {
            val result = ConvergenceMachine.decide(state(budget = restartBudget(maxAttempts = 0)), stopped, now)
            (result.decision as ConvergenceDecision.CrashLooped).reason.isNotEmpty() shouldBe true
        }

        test("no budget means unlimited restarts (no cap)") {
            val result = ConvergenceMachine.decide(
                state(budget = null, restartCount = 9, windowStartEpochMillis = now - 1),
                stopped,
                now
            )
            result.decision shouldBe ConvergenceDecision.EnsureRunning(false)
        }

        test("no_restart suppresses the crash-restart while desired stays RUNNING") {
            val result = ConvergenceMachine.decide(state(noRestart = true), stopped, now)
            result.decision shouldBe ConvergenceDecision.NoOp
        }

        // ── recreate-if-diff (retirement of needs_recreate) ────────────────────

        val specA = baseSpec.toBuilder().setImage("img-a").build()
        val specB = baseSpec.toBuilder().setImage("img-b").build()

        test("stopped with a spec that differs from the applied spec recreates") {
            val result = ConvergenceMachine.decide(state(spec = specB, appliedSpec = specA), stopped, now)
            result.decision shouldBe ConvergenceDecision.EnsureRunning(recreate = true)
        }

        test("stopped with a spec equal to the applied spec does not recreate") {
            val result = ConvergenceMachine.decide(state(spec = specA, appliedSpec = specA), stopped, now)
            result.decision shouldBe ConvergenceDecision.EnsureRunning(recreate = false)
        }

        test("unknown applied spec (fresh agent process) does not recreate") {
            val result = ConvergenceMachine.decide(state(spec = specA, appliedSpec = null), stopped, now)
            result.decision shouldBe ConvergenceDecision.EnsureRunning(recreate = false)
        }

        test("unknown applied spec never recreates, even on a user restart") {
            val result = ConvergenceMachine.decide(state(spec = specA, appliedSpec = null, forceRestart = true), running, now)
            result.decision shouldBe ConvergenceDecision.ConditionalRestart(recreate = false)
        }

        test("running + force_restart with a spec diff recreates; without a diff it does not") {
            val diff = ConvergenceMachine.decide(state(spec = specB, appliedSpec = specA, forceRestart = true), running, now)
            diff.decision shouldBe ConvergenceDecision.ConditionalRestart(recreate = true)

            val same = ConvergenceMachine.decide(state(spec = specA, appliedSpec = specA, forceRestart = true), running, now)
            same.decision shouldBe ConvergenceDecision.ConditionalRestart(recreate = false)
        }

        test("running without force_restart and a spec diff is a NoOp (reconfigure at next start)") {
            val result = ConvergenceMachine.decide(state(spec = specB, appliedSpec = specA), running, now)
            result.decision shouldBe ConvergenceDecision.NoOp
        }
    })