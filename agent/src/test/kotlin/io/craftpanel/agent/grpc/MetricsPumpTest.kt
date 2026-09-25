package io.craftpanel.agent.grpc

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.desired.JvmMetricsPolicy
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.MetricsCollector
import io.craftpanel.agent.docker.PlayerCountProbe
import io.craftpanel.agent.docker.RouterSupervisor
import io.craftpanel.agent.docker.RunningContainer
import io.craftpanel.proto.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

class MetricsPumpTest :
    FunSpec({

        val containerManager: ContainerManager = mockk(relaxed = true)
        val metricsCollector: MetricsCollector = mockk(relaxed = true)
        val routerSupervisor: RouterSupervisor = mockk(relaxed = true)
        val channel = Channel<AgentMessage>(Channel.UNLIMITED)
        val out = AgentOutbound(channel, "node-1")

        val symlinkTempRoot = Files.createTempDirectory("metrics-pump-test")
            .toFile()

        fun config(pollSeconds: Int = 1) = AgentConfig(
            profile = "dev",
            masterAddress = "localhost",
            masterPort = 50051,
            tlsCertPath = "",
            caCertFilePath = "/etc/craftpanel/grpc-ca.crt",
            bootstrapToken = "test-token-16chars",
            keyFilePath = "/etc/craftpanel/node.key",
            dockerSocketPath = "unix:///var/run/docker.sock",
            dataBasePath = symlinkTempRoot.absolutePath,
            hostDataBasePath = symlinkTempRoot.absolutePath,
            serversByNameRoot = symlinkTempRoot.absolutePath,
            backupsByServerRoot = symlinkTempRoot.absolutePath,
            mcRouterImage = "itzg/mc-router:latest",
            mcRouterUpdateOnStart = false,
            publicIpUrl = "",
            hostnameOverride = "",
            systemReservedRamMb = 0,
            systemReservedCpuMillicores = 0,
            craftpanelNetwork = "craftpanel",
            containerNamePrefix = "craftpanel",
            metricsPollIntervalSeconds = pollSeconds,
            masterHttpPort = 80,
            privateIpOverride = "",
            mcRouterContainerName = ""
        )

        fun pump(cpuLimit: (String) -> Int = { 0 }, probe: (String) -> PlayerCountProbe? = { null }, jvmPolicy: (String) -> JvmMetricsPolicy? = { null }) =
            MetricsPump(config(), containerManager, metricsCollector, routerSupervisor, out, cpuLimit, probe, jvmPolicy)

        fun drain(): List<AgentMessage> = generateSequence { channel.tryReceive().getOrNull() }.toList()

        beforeTest {
            clearMocks(containerManager, metricsCollector, routerSupervisor)
            drain()
        }

        test("tick emits node metrics with the router state") {
            every { metricsCollector.collect() } returns nodeMetricsUpdate { cpuPercent = 42.0 }
            every { routerSupervisor.isRunning } returns true
            every { containerManager.listRunningContainers() } returns emptyList()

            runTest { pump().tick() }

            val msg = channel.tryReceive().getOrThrow()
            msg.hasNodeMetrics() shouldBe true
            msg.nodeMetrics.cpuPercent shouldBe 42.0
            msg.nodeMetrics.routerRunning shouldBe true
        }

        test("tick fans out container metrics with the CPU limit and emits player updates") {
            every { metricsCollector.collect() } returns nodeMetricsUpdate {}
            every { routerSupervisor.isRunning } returns false
            every { containerManager.listRunningContainers() } returns
                listOf(RunningContainer("srv-1", "cid-1"))
            every { metricsCollector.collectContainerMetrics("srv-1", "cid-1", 2048) } returns
                containerMetricsUpdate { cpuPercent = 10.0 }
            every { metricsCollector.collectPlayerCount("srv-1", "cid-1", 25565, false) } returns
                playerUpdate { playerCount = 3 }

            runTest { pump(cpuLimit = { 2048 }, probe = { PlayerCountProbe(25565, false) }).tick() }

            val msgs = drain()
            msgs.any { it.hasContainerMetrics() && it.containerMetrics.cpuPercent == 10.0 } shouldBe true
            msgs.any { it.hasPlayerUpdate() && it.playerUpdate.playerCount == 3 } shouldBe true
            verify { metricsCollector.collectContainerMetrics("srv-1", "cid-1", 2048) }
        }

        test("tick attaches JVM stats when enabled") {
            every { metricsCollector.collect() } returns nodeMetricsUpdate {}
            every { containerManager.listRunningContainers() } returns
                listOf(RunningContainer("srv-1", "cid-1"))
            every { metricsCollector.collectContainerMetrics("srv-1", "cid-1", 0) } returns
                containerMetricsUpdate { cpuPercent = 10.0 }
            every { metricsCollector.collectJvmStats("cid-1") } returns
                jvmStats {
                    heapUsedBytes = 1_000_000
                    heapMaxBytes = 4_000_000
                    nonHeapUsedBytes = 250_000
                }

            runTest { pump(jvmPolicy = { JvmMetricsPolicy(enabled = true, pollIntervalSeconds = 30) }).tick() }

            val cm = drain().first { it.hasContainerMetrics() }.containerMetrics
            cm.hasJvm() shouldBe true
            cm.jvm.heapUsedBytes shouldBe 1_000_000
            cm.jvm.heapMaxBytes shouldBe 4_000_000
            cm.jvm.nonHeapUsedBytes shouldBe 250_000
            verify(exactly = 1) { metricsCollector.collectJvmStats("cid-1") }
        }

        test("tick does not collect JVM stats when disabled") {
            every { metricsCollector.collect() } returns nodeMetricsUpdate {}
            every { containerManager.listRunningContainers() } returns
                listOf(RunningContainer("srv-1", "cid-1"))
            every { metricsCollector.collectContainerMetrics("srv-1", "cid-1", 0) } returns
                containerMetricsUpdate { cpuPercent = 10.0 }

            runTest { pump(jvmPolicy = { JvmMetricsPolicy(enabled = false, pollIntervalSeconds = 30) }).tick() }

            val cm = drain().first { it.hasContainerMetrics() }.containerMetrics
            cm.hasJvm() shouldBe false
            verify(exactly = 0) { metricsCollector.collectJvmStats(any()) }
        }

        test("tick emits container metrics without JVM when collection yields null") {
            every { metricsCollector.collect() } returns nodeMetricsUpdate {}
            every { containerManager.listRunningContainers() } returns
                listOf(RunningContainer("srv-1", "cid-1"))
            every { metricsCollector.collectContainerMetrics("srv-1", "cid-1", 0) } returns
                containerMetricsUpdate { cpuPercent = 10.0 }
            every { metricsCollector.collectJvmStats("cid-1") } returns null

            runTest { pump().tick() }

            val cm = drain().first { it.hasContainerMetrics() }.containerMetrics
            cm.cpuPercent shouldBe 10.0
            cm.hasJvm() shouldBe false
        }

        test("tick throttles JVM sampling to the poll interval") {
            every { metricsCollector.collect() } returns nodeMetricsUpdate {}
            every { containerManager.listRunningContainers() } returns
                listOf(RunningContainer("srv-1", "cid-1"))
            every { metricsCollector.collectContainerMetrics("srv-1", "cid-1", 0) } returns
                containerMetricsUpdate { cpuPercent = 10.0 }
            every { metricsCollector.collectJvmStats("cid-1") } returns jvmStats { heapUsedBytes = 1 }

            runTest {
                val pump = pump(jvmPolicy = { JvmMetricsPolicy(enabled = true, pollIntervalSeconds = 30) })
                pump.tick()
                pump.tick()
            }

            // Second tick is within the 30s window, so the JVM probe runs only once.
            verify(exactly = 1) { metricsCollector.collectJvmStats("cid-1") }
        }

        test("tick skips player counts when the server cannot be probed") {
            every { metricsCollector.collect() } returns nodeMetricsUpdate {}
            every { containerManager.listRunningContainers() } returns
                listOf(RunningContainer("srv-1", "cid-1"))

            runTest { pump(probe = { null }).tick() }

            verify(exactly = 0) { metricsCollector.collectPlayerCount(any(), any(), any(), any()) }
        }

        test("run swallows a failing tick and keeps looping") {
            every { metricsCollector.collect() } throws RuntimeException("boom")

            runTest {
                val job = launch { pump().run() }
                advanceTimeBy(2_500)
                job.isActive shouldBe true
                job.cancel()
            }
        }
    })
