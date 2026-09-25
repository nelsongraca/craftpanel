package io.craftpanel.agent.grpc

import io.craftpanel.agent.config.RestartBudgetSettings
import io.craftpanel.agent.config.RuntimeSettings
import io.craftpanel.agent.config.RuntimeSettingsStore
import io.craftpanel.agent.desired.JvmMetricsPolicy
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.MetricsCollector
import io.craftpanel.agent.docker.PlayerCountProbe
import io.craftpanel.agent.docker.RouterSupervisor
import io.craftpanel.agent.docker.RunningContainer
import io.craftpanel.agent.runtime.OutboundSink
import io.craftpanel.proto.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

class MetricsPumpTest :
    FunSpec({

        val containerManager: ContainerManager = mockk(relaxed = true)
        val metricsCollector: MetricsCollector = mockk(relaxed = true)
        val routerSupervisor: RouterSupervisor = mockk(relaxed = true)
        var channel = Channel<AgentMessage>(Channel.UNLIMITED)
        val sink = OutboundSink()
        val store = RuntimeSettingsStore(
            Files.createTempDirectory("metrics-pump-settings").resolve("runtime-settings.json").toFile()
        )

        fun connect() {
            channel = Channel(Channel.UNLIMITED)
            sink.attach(AgentOutbound(channel, "node-1"))
        }

        fun pump(
            cpuLimit: (String) -> Int = { 0 },
            probe: (String) -> PlayerCountProbe? = { null },
            jvmPolicy: (String) -> JvmMetricsPolicy? = { null },
            intervalSeconds: Int = 1,
            concurrency: Int = 8
        ): MetricsPump {
            store.apply(
                RuntimeSettings(
                    metricsPollIntervalSeconds = intervalSeconds,
                    metricsCollectionConcurrency = concurrency,
                    restartBudget = RestartBudgetSettings()
                )
            )
            return MetricsPump(containerManager, metricsCollector, routerSupervisor, sink, store, cpuLimit, probe, jvmPolicy)
        }

        fun semaphore(): Semaphore = Semaphore(store.current().metricsCollectionConcurrency)

        fun drain(): List<AgentMessage> = generateSequence { channel.tryReceive().getOrNull() }.toList()

        beforeTest {
            clearMocks(containerManager, metricsCollector, routerSupervisor)
            connect()
            drain()
        }

        test("tick emits node metrics with the router state") {
            every { metricsCollector.collect() } returns nodeMetricsUpdate { cpuPercent = 42.0 }
            every { routerSupervisor.isRunning } returns true
            every { containerManager.listRunningContainers() } returns emptyList()

            runTest { pump().tick(semaphore()) }

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

            runTest { pump(cpuLimit = { 2048 }, probe = { PlayerCountProbe(25565, false) }).tick(semaphore()) }

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

            runTest { pump(jvmPolicy = { JvmMetricsPolicy(enabled = true, pollIntervalSeconds = 30) }).tick(semaphore()) }

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

            runTest { pump(jvmPolicy = { JvmMetricsPolicy(enabled = false, pollIntervalSeconds = 30) }).tick(semaphore()) }

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

            runTest { pump().tick(semaphore()) }

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
                pump.tick(semaphore())
                pump.tick(semaphore())
            }

            // Second tick is within the 30s window, so the JVM probe runs only once.
            verify(exactly = 1) { metricsCollector.collectJvmStats("cid-1") }
        }

        test("tick skips player counts when the server cannot be probed") {
            every { metricsCollector.collect() } returns nodeMetricsUpdate {}
            every { containerManager.listRunningContainers() } returns
                listOf(RunningContainer("srv-1", "cid-1"))

            runTest { pump(probe = { null }).tick(semaphore()) }

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

        test("run performs no collection while the sink is disconnected") {
            every { metricsCollector.collect() } returns nodeMetricsUpdate {}
            every { containerManager.listRunningContainers() } returns emptyList()

            runTest {
                sink.detach()
                val job = launch { pump().run() }
                advanceTimeBy(5_000)
                verify(exactly = 0) { metricsCollector.collect() }

                // Reconnecting resumes collection with the current settings.
                connect()
                advanceTimeBy(2_000)
                verify(atLeast = 1) { metricsCollector.collect() }
                job.cancel()
            }
        }
    })
