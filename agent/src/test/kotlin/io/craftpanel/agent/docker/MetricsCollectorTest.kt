package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import java.io.File

class MetricsCollectorTest :
    FunSpec({
        val docker: DockerClient = mockk()
        val collector = MetricsCollector(docker)

        test("collectCapacity returns positive RAM") {
            val (ramMb, _) = collector.collectCapacity()
            (ramMb > 0) shouldBe true
        }

        test("collectCapacity cpu millicores reflects host cores, not the container limit") {
            val (_, cpuMillicores) = collector.collectCapacity()
            (cpuMillicores > 0) shouldBe true
            (cpuMillicores % 1000) shouldBe 0
            val hostCores = File("/proc/cpuinfo").readLines().count { it.startsWith("processor") }
            if (hostCores > 0) cpuMillicores shouldBe hostCores * 1000
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

        // ── normalizeCpuPercent ──────────────────────────────────────────────

        test("1 core used under a 1-core cap is 100%") {
            normalizeCpuPercent(coresUsed = 1.0, hostCores = 8, cpuLimitMillicores = 1000) shouldBe 100.0
        }

        test("1 core used under a 2-core cap is 50%") {
            normalizeCpuPercent(coresUsed = 1.0, hostCores = 8, cpuLimitMillicores = 2000) shouldBe 50.0
        }

        test("0.5 cores used under a 2-core cap is 25%") {
            normalizeCpuPercent(coresUsed = 0.5, hostCores = 8, cpuLimitMillicores = 2000) shouldBe 25.0
        }

        test("no cap reports against total host cores") {
            normalizeCpuPercent(coresUsed = 1.0, hostCores = 4, cpuLimitMillicores = 0) shouldBe 25.0
        }

        test("cap exceeded by host cores is clamped to host") {
            // 4-core cap on a 2-core host: 2 cores used is physically 100%, not 50%.
            normalizeCpuPercent(coresUsed = 2.0, hostCores = 2, cpuLimitMillicores = 4000) shouldBe 100.0
        }

        test("result is clamped to 100 on quota-period overshoot") {
            normalizeCpuPercent(coresUsed = 1.5, hostCores = 8, cpuLimitMillicores = 1000) shouldBe 100.0
        }

        test("zero usage is 0%") {
            normalizeCpuPercent(coresUsed = 0.0, hostCores = 8, cpuLimitMillicores = 1000) shouldBe 0.0
        }

        test("host core count below one does not divide by zero") {
            normalizeCpuPercent(coresUsed = 0.0, hostCores = 0, cpuLimitMillicores = 0) shouldBe 0.0
        }
    })
