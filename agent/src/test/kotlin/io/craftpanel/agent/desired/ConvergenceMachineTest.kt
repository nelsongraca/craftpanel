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

        val spec = StartContainerCommand.getDefaultInstance()

        fun state(
            desired: ServerDesiredState.Desired = ServerDesiredState.Desired.RUNNING,
            budget: RestartBudget? = restartBudget(),
            restartCount: Int = 0,
            windowStartEpochMillis: Long? = null,
            forceRestart: Boolean = false,
            force: Boolean = false,
            noRestart: Boolean = false,
        ) = DesiredState(
            serverId = "srv-1",
            desired = desired,
            spec = spec,
            budget = budget,
            appliedSpec = null,
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
            val result = ConvergenceMachine.decide(state(forceRestart = true), running, now)
            result.decision shouldBe ConvergenceDecision.ConditionalRestart
            result.next.forceRestart shouldBe false
        }

        test("desired=RUNNING, stopped, force_restart pending consumes as an ordinary start") {
            val result = ConvergenceMachine.decide(state(forceRestart = true), stopped, now)
            result.decision shouldBe ConvergenceDecision.EnsureRunning
            result.next.forceRestart shouldBe false
        }

        test("desired=RUNNING with container absent is provisioning — never budget-capped") {
            val exhausted = state(restartCount = 10, windowStartEpochMillis = now - 1)
            val result = ConvergenceMachine.decide(exhausted, absent, now)
            result.decision shouldBe ConvergenceDecision.EnsureRunning
        }

        test("first crash within budget issues EnsureRunning and advances the counter") {
            val result = ConvergenceMachine.decide(state(), stopped, now)
            result.decision shouldBe ConvergenceDecision.EnsureRunning
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
            result.decision shouldBe ConvergenceDecision.EnsureRunning
        }

        test("no_restart suppresses the crash-restart while desired stays RUNNING") {
            val result = ConvergenceMachine.decide(state(noRestart = true), stopped, now)
            result.decision shouldBe ConvergenceDecision.NoOp
        }
    })