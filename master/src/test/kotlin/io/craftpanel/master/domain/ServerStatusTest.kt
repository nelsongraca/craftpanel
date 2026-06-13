package io.craftpanel.master.domain

import io.craftpanel.master.grpc.mapContainerState
import io.craftpanel.master.grpc.mapMissingContainer
import io.craftpanel.proto.ContainerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerStatusTest {

    // ── Predicates ───────────────────────────────────────────────────────────

    @Test
    fun `isRunning true for HEALTHY STARTING UNHEALTHY`() {
        assertTrue(ServerStatus.HEALTHY.isRunning)
        assertTrue(ServerStatus.STARTING.isRunning)
        assertTrue(ServerStatus.UNHEALTHY.isRunning)
    }

    @Test
    fun `isRunning false for STOPPED and STOPPING`() {
        assertFalse(ServerStatus.STOPPED.isRunning)
        assertFalse(ServerStatus.STOPPING.isRunning)
    }

    @Test
    fun `isStopped true only for STOPPED`() {
        assertTrue(ServerStatus.STOPPED.isStopped)
        ServerStatus.entries.filter { it != ServerStatus.STOPPED }
            .forEach { assertFalse(it.isStopped) }
    }

    @Test
    fun `toDb and fromDb round-trip`() {
        ServerStatus.entries.forEach { status ->
            assertEquals(status, ServerStatus.fromDb(status.toDb()))
        }
    }

    // ── Container→status mapper matrix ───────────────────────────────────────

    @Test
    fun `RUNNING container when not HEALTHY → HEALTHY`() {
        val nonHealthy = ServerStatus.entries.filter { it != ServerStatus.HEALTHY }
        nonHealthy.forEach { db ->
            assertEquals(ServerStatus.HEALTHY, mapContainerState(ContainerState.RunState.RUNNING, db), "db=$db")
        }
    }

    @Test
    fun `RUNNING container when already HEALTHY → null`() {
        assertNull(mapContainerState(ContainerState.RunState.RUNNING, ServerStatus.HEALTHY))
    }

    @Test
    fun `STOPPED container when isRunning → STOPPED`() {
        listOf(ServerStatus.HEALTHY, ServerStatus.STARTING, ServerStatus.UNHEALTHY).forEach { db ->
            assertEquals(ServerStatus.STOPPED, mapContainerState(ContainerState.RunState.STOPPED, db), "db=$db")
        }
    }

    @Test
    fun `STOPPED container when already STOPPED or STOPPING → null`() {
        assertNull(mapContainerState(ContainerState.RunState.STOPPED, ServerStatus.STOPPED))
        assertNull(mapContainerState(ContainerState.RunState.STOPPED, ServerStatus.STOPPING))
    }

    @Test
    fun `EXITED container when not UNHEALTHY → UNHEALTHY`() {
        val nonUnhealthy = ServerStatus.entries.filter { it != ServerStatus.UNHEALTHY }
        nonUnhealthy.forEach { db ->
            assertEquals(ServerStatus.UNHEALTHY, mapContainerState(ContainerState.RunState.EXITED, db), "db=$db")
        }
    }

    @Test
    fun `EXITED container when already UNHEALTHY → null`() {
        assertNull(mapContainerState(ContainerState.RunState.EXITED, ServerStatus.UNHEALTHY))
    }

    @Test
    fun `missing container when not STOPPED → STOPPED`() {
        val nonStopped = ServerStatus.entries.filter { it != ServerStatus.STOPPED }
        nonStopped.forEach { db ->
            assertEquals(ServerStatus.STOPPED, mapMissingContainer(db), "db=$db")
        }
    }

    @Test
    fun `missing container when already STOPPED → null`() {
        assertNull(mapMissingContainer(ServerStatus.STOPPED))
    }
}
