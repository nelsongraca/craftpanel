package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk

class MetricsCollectorTest :
    FunSpec({
        val docker: DockerClient = mockk()
        val collector = MetricsCollector(docker)

        test("collectCapacity returns positive RAM") {
            val (ramMb, _) = collector.collectCapacity()
            (ramMb > 0) shouldBe true
        }

        test("collectCapacity cpu shares equal availableProcessors times 1024") {
            val (_, cpuShares) = collector.collectCapacity()
            cpuShares shouldBe Runtime.getRuntime()
                .availableProcessors() * 1024
        }

        test("collect returns a non-null NodeMetricsUpdate") {
            collector.collect() shouldNotBe null
        }

        test("collect returns non-negative RAM metrics") {
            val result = collector.collect()
            (result.ramTotalMb >= 0) shouldBe true
            (result.ramUsedMb >= 0) shouldBe true
        }

        test("collectContainerMetrics handles unknown container id without throwing") {
            collector.collectContainerMetrics("server-1", "nonexistent-container-id")
            collector shouldNotBe null
        }
    })
