package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MetricsCollectorTest {

    private val docker: DockerClient = mockk()
    private val collector = MetricsCollector(docker)

    @Test
    fun `collectCapacity returns positive RAM`() {
        val (ramMb, _) = collector.collectCapacity()
        assertTrue(ramMb > 0, "Expected positive RAM but got $ramMb")
    }

    @Test
    fun `collectCapacity cpu shares equal availableProcessors times 1024`() {
        val (_, cpuShares) = collector.collectCapacity()
        assertEquals(
            Runtime.getRuntime()
                .availableProcessors() * 1024, cpuShares
        )
    }

    @Test
    fun `collect returns a non-null NodeMetricsUpdate`() {
        assertNotNull(collector.collect())
    }

    @Test
    fun `collect returns non-negative RAM metrics`() {
        val result = collector.collect()
        assertTrue(result.ramTotalMb >= 0)
        assertTrue(result.ramUsedMb >= 0)
    }

    @Test
    fun `collectContainerMetrics handles unknown container id without throwing`() {
        // Mock Docker returns nothing; latch times out → null returned gracefully
        collector.collectContainerMetrics("server-1", "nonexistent-container-id")
        assertNotNull(collector)
    }
}
