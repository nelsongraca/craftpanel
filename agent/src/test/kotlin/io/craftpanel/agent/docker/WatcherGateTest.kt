package io.craftpanel.agent.docker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WatcherGateTest :
    FunSpec({

        test("unmanaged server death is not reported") {
            WatcherGate().shouldReportDie("srv-1") shouldBe false
        }

        test("death after markStarted is reported") {
            val gate = WatcherGate()
            gate.markStarted("srv-1")
            gate.shouldReportDie("srv-1") shouldBe true
        }

        test("death is suppressed while stopping") {
            val gate = WatcherGate()
            gate.markStarted("srv-1")
            gate.markStopping("srv-1")
            gate.shouldReportDie("srv-1") shouldBe false
        }

        test("restarting re-enables crash reporting") {
            val gate = WatcherGate()
            gate.markStarted("srv-1")
            gate.markStopping("srv-1")
            gate.markStarted("srv-1")
            gate.shouldReportDie("srv-1") shouldBe true
        }

        test("markRemoved clears ownership; a fresh start re-manages") {
            val gate = WatcherGate()
            gate.markStarted("srv-1")
            gate.markRemoved("srv-1")
            gate.shouldReportDie("srv-1") shouldBe false
            gate.markStarted("srv-1")
            gate.shouldReportDie("srv-1") shouldBe true
        }

        test("markManaged seeds ownership without a start") {
            val gate = WatcherGate()
            gate.markManaged("srv-1")
            gate.shouldReportDie("srv-1") shouldBe true
        }

        test("markManaged does not clear an intentional-stop flag") {
            val gate = WatcherGate()
            gate.markStarted("srv-1")
            gate.markStopping("srv-1")
            gate.markManaged("srv-1")
            gate.shouldReportDie("srv-1") shouldBe false
            gate.clearStopping("srv-1")
            gate.shouldReportDie("srv-1") shouldBe true
        }

        test("markRemoved clears ownership and stopping") {
            val gate = WatcherGate()
            gate.markManaged("srv-1")
            gate.markStopping("srv-1")
            gate.markRemoved("srv-1")
            gate.shouldReportDie("srv-1") shouldBe false
            gate.managedCount shouldBe 0
            gate.stoppingCount shouldBe 0
        }

        test("servers are tracked independently") {
            val gate = WatcherGate()
            gate.markStarted("srv-1")
            gate.markStarted("srv-2")
            gate.markStopping("srv-1")
            gate.shouldReportDie("srv-1") shouldBe false
            gate.shouldReportDie("srv-2") shouldBe true
        }
    })
