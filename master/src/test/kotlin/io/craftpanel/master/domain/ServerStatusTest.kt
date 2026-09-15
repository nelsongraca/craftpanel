package io.craftpanel.master.domain

import io.craftpanel.proto.ServerStatusUpdate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ServerStatusTest :
    FunSpec({
        // ── Predicates ───────────────────────────────────────────────────────────

        test("isRunning true for HEALTHY STARTING UNHEALTHY") {
            ServerStatus.HEALTHY.isRunning shouldBe true
            ServerStatus.STARTING.isRunning shouldBe true
            ServerStatus.UNHEALTHY.isRunning shouldBe true
        }

        test("isRunning false for STOPPED and STOPPING") {
            ServerStatus.STOPPED.isRunning shouldBe false
            ServerStatus.STOPPING.isRunning shouldBe false
        }

        test("isStopped true only for STOPPED") {
            ServerStatus.STOPPED.isStopped shouldBe true
            ServerStatus.entries.filter { it != ServerStatus.STOPPED }
                .forEach { it.isStopped shouldBe false }
        }

        test("toDb and fromDb round-trip") {
            ServerStatus.entries.forEach { status ->
                ServerStatus.fromDb(status.toDb()) shouldBe status
            }
        }

        // ── fromProto mapping ────────────────────────────────────────────────────

        test("fromProto maps all proto values to domain") {
            ServerStatus.fromProto(ServerStatusUpdate.ServerStatus.STOPPED) shouldBe ServerStatus.STOPPED
            ServerStatus.fromProto(ServerStatusUpdate.ServerStatus.STARTING) shouldBe ServerStatus.STARTING
            ServerStatus.fromProto(ServerStatusUpdate.ServerStatus.HEALTHY) shouldBe ServerStatus.HEALTHY
            ServerStatus.fromProto(ServerStatusUpdate.ServerStatus.UNHEALTHY) shouldBe ServerStatus.UNHEALTHY
            ServerStatus.fromProto(ServerStatusUpdate.ServerStatus.CRASH_LOOPED) shouldBe ServerStatus.CRASH_LOOPED
        }

        test("fromProto throws on UNSPECIFIED") {
            shouldThrow<IllegalStateException> {
                ServerStatus.fromProto(ServerStatusUpdate.ServerStatus.SERVER_STATUS_UNSPECIFIED)
            }
        }

        test("fromProto throws on UNRECOGNIZED") {
            shouldThrow<IllegalStateException> {
                ServerStatus.fromProto(ServerStatusUpdate.ServerStatus.UNRECOGNIZED)
            }
        }
    })