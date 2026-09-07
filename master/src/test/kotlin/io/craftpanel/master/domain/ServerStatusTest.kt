package io.craftpanel.master.domain

import io.craftpanel.master.service.mapContainerState
import io.craftpanel.master.service.mapMissingContainer
import io.craftpanel.proto.ContainerState
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

        // ── Container→status mapper matrix ───────────────────────────────────────

        test("RUNNING container when not HEALTHY and not STOPPING → HEALTHY") {
            val transitional = ServerStatus.entries.filter {
                it != ServerStatus.HEALTHY && it != ServerStatus.STOPPING
            }
            transitional.forEach { db ->
                mapContainerState(ContainerState.RunState.RUNNING, db) shouldBe ServerStatus.HEALTHY
            }
        }

        test("RUNNING container when STOPPING → null (stop in flight)") {
            mapContainerState(ContainerState.RunState.RUNNING, ServerStatus.STOPPING) shouldBe null
        }

        test("RUNNING container when already HEALTHY → null") {
            mapContainerState(ContainerState.RunState.RUNNING, ServerStatus.HEALTHY) shouldBe null
        }

        test("STOPPED container when isRunning → STOPPED") {
            listOf(ServerStatus.HEALTHY, ServerStatus.STARTING, ServerStatus.UNHEALTHY).forEach { db ->
                mapContainerState(ContainerState.RunState.STOPPED, db) shouldBe ServerStatus.STOPPED
            }
        }

        test("STOPPED container when already STOPPED or STOPPING → null") {
            mapContainerState(ContainerState.RunState.STOPPED, ServerStatus.STOPPED) shouldBe null
            mapContainerState(ContainerState.RunState.STOPPED, ServerStatus.STOPPING) shouldBe null
        }

        test("EXITED container when STOPPING → STOPPED (stop completed)") {
            mapContainerState(ContainerState.RunState.EXITED, ServerStatus.STOPPING) shouldBe ServerStatus.STOPPED
        }

        test("EXITED container when not UNHEALTHY and not STOPPING → UNHEALTHY") {
            val nonUnhealthy = ServerStatus.entries.filter {
                it != ServerStatus.UNHEALTHY && it != ServerStatus.STOPPING
            }
            nonUnhealthy.forEach { db ->
                mapContainerState(ContainerState.RunState.EXITED, db) shouldBe ServerStatus.UNHEALTHY
            }
        }

        test("EXITED container when already UNHEALTHY → null") {
            mapContainerState(ContainerState.RunState.EXITED, ServerStatus.UNHEALTHY) shouldBe null
        }

        test("missing container when not STOPPED → STOPPED") {
            val nonStopped = ServerStatus.entries.filter { it != ServerStatus.STOPPED }
            nonStopped.forEach { db ->
                mapMissingContainer(db) shouldBe ServerStatus.STOPPED
            }
        }

        test("missing container when already STOPPED → null") {
            mapMissingContainer(ServerStatus.STOPPED) shouldBe null
        }

        // ── fromProto mapping ────────────────────────────────────────────────────

        test("fromProto maps all 4 proto values to domain") {
            ServerStatus.fromProto(ServerStatusUpdate.ServerStatus.STOPPED) shouldBe ServerStatus.STOPPED
            ServerStatus.fromProto(ServerStatusUpdate.ServerStatus.STARTING) shouldBe ServerStatus.STARTING
            ServerStatus.fromProto(ServerStatusUpdate.ServerStatus.HEALTHY) shouldBe ServerStatus.HEALTHY
            ServerStatus.fromProto(ServerStatusUpdate.ServerStatus.UNHEALTHY) shouldBe ServerStatus.UNHEALTHY
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
