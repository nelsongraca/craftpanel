package io.craftpanel.agent.desired

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.FakeContainerManager
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.NetworkManager
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.agent.grpc.handlers.DesiredStateHandler
import io.craftpanel.proto.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class ConvergenceLoopTest :
    FunSpec({

        val symlinkTempRoot = Files.createTempDirectory("convergence-loop-test")
            .toFile()
        val config = AgentConfig(
            profile = "dev",
            masterAddress = "localhost",
            masterPort = 50051,
            tlsCertPath = "",
            caCertFilePath = "/etc/craftpanel/grpc-ca.crt",
            bootstrapToken = "test-token-16chars",
            keyFilePath = "/etc/craftpanel/node.key",
            dockerSocketPath = "unix:///var/run/docker.sock",
            agentVersion = "test",
            dataBasePath = symlinkTempRoot.absolutePath,
            hostDataBasePath = symlinkTempRoot.absolutePath,
            serversByNameRoot = symlinkTempRoot.absolutePath,
            backupsByServerRoot = symlinkTempRoot.absolutePath,
            mcRouterImage = "itzg/mc-router:latest",
            mcRouterUpdateOnStart = false,
            publicIpUrl = "",
            hostnameOverride = "",
            systemReservedRamMb = 0,
            systemReservedCpuShares = 0,
            craftpanelNetwork = "craftpanel",
            containerNamePrefix = "craftpanel",
            metricsPollIntervalSeconds = 60,
            masterHttpPort = 80,
            privateIpOverride = "",
            mcRouterContainerName = ""
        )

        fun newScope(): CoroutineScope = CoroutineScope(Dispatchers.Default)

        fun newLoop(
            cm: ContainerManager,
            outbound: AgentOutbound,
            scope: CoroutineScope = newScope(),
            store: DesiredStateStore = DesiredStateStore(),
        ) = ConvergenceLoop(
            store = store,
            operator = ContainerOperator(cm, mockk<NetworkManager>(relaxed = true), config),
            containerNamePrefix = config.containerNamePrefix,
            out = outbound,
            scope = scope,
        )

        fun startCmd(serverId: String = "srv-1", serverName: String = "myserver", image: String = "itzg/minecraft-server:latest") =
            startContainerCommand {
                containerName = "craftpanel-$serverId"
                this.serverId = serverId
                this.serverName = serverName
                this.image = image
                hostPort = 25565
                internalListenPort = 25565
            }

        fun desiredRunning(serverId: String = "srv-1", spec: StartContainerCommand? = null) =
            serverDesiredState {
                this.serverId = serverId
                desired = ServerDesiredState.Desired.RUNNING
                spec?.let { this.spec = it }
                restartBudget = restartBudget {
                    maxAttempts = 3
                    windowSeconds = 600
                }
            }

        fun Channel<AgentMessage>.statuses(): List<ServerStatusUpdate> = buildList {
            while (true) {
                val r = tryReceive()
                if (r.isSuccess) {
                    val msg = r.getOrThrow()
                    if (msg.hasServerStatus()) add(msg.serverStatus)
                } else break
            }
        }

        fun newOutbound(): Pair<Channel<AgentMessage>, AgentOutbound> {
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)
            return channel to AgentOutbound(channel, "node-1")
        }

        /** Polls until [status] arrives (5s timeout), then returns. */
        fun awaitStatus(channel: Channel<AgentMessage>, status: ServerStatusUpdate.ServerStatus) {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val r = channel.tryReceive()
                if (r.isSuccess) {
                    val msg = r.getOrThrow()
                    if (msg.hasServerStatus() && msg.serverStatus.status == status) return
                } else {
                    Thread.sleep(10)
                }
            }
            error("Timed out waiting for server status $status")
        }

        // ── legacy start/stop/restart ─────────────────────────────────────────

        test("legacy start on missing container pulls, creates, starts and reports HEALTHY") {
            val cm = FakeContainerManager()
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking { loop.applyLegacyStart(startCmd()).join() }

            cm.calls.filter { it.startsWith("pull:") || it.startsWith("create:") || it.startsWith("start:") } shouldBe
                listOf("pull:itzg/minecraft-server:latest", "create:craftpanel-srv-1", "start:craftpanel-srv-1")
            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.RUNNING
            cm.gate.shouldReportDie("srv-1") shouldBe true
            channel.statuses()
                .last().status shouldBe ServerStatusUpdate.ServerStatus.HEALTHY
        }

        test("legacy start creates a servers-by-name symlink pointing at the server's canonical data dir") {
            val cm = FakeContainerManager()
            val byNameRoot = Files.createTempDirectory("by-name")
                .toFile()
            val dataConfig = config.copy(serversByNameRoot = byNameRoot.absolutePath)
            val serverId = "srv-symlink"
            val srvName = "survival-world"
            val (_, out) = newOutbound()

            val loop = ConvergenceLoop(
                store = DesiredStateStore(),
                operator = ContainerOperator(cm, mockk<NetworkManager>(relaxed = true), dataConfig),
                containerNamePrefix = config.containerNamePrefix,
                out = out,
                scope = newScope(),
            )
            runBlocking {
                loop.applyLegacyStart(
                    startCmd(serverId = serverId, serverName = srvName)
                ).join()
            }

            val link = java.nio.file.Path.of(byNameRoot.absolutePath, srvName)
            Files.exists(link) shouldBe true
            Files.isSymbolicLink(link) shouldBe true
            byNameRoot.deleteRecursively()
        }

        test("legacy start recreates the old container when the stored spec differs from the applied spec") {
            val cm = FakeContainerManager()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            cm.calls.clear()
            val store = DesiredStateStore()
            val oldSpec = startCmd(image = "old-image")
            store.upsert("srv-1") { it.copy(spec = oldSpec, appliedSpec = oldSpec) }
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out, store = store)

            runBlocking { loop.applyLegacyStart(startCmd(image = "new-image")).join() }

            cm.calls.filter { it.startsWith("remove:") || it.startsWith("create:") || it.startsWith("start:") } shouldBe
                listOf("remove:craftpanel-srv-1", "create:craftpanel-srv-1", "start:craftpanel-srv-1")
            cm.gate.shouldReportDie("srv-1") shouldBe true
            channel.statuses()
                .last().status shouldBe ServerStatusUpdate.ServerStatus.HEALTHY
        }

        test("legacy start on an existing container with an unknown applied spec skips the create path") {
            val cm = FakeContainerManager()
            cm.createContainer(startCmd())
            cm.calls.clear()
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking { loop.applyLegacyStart(startCmd()).join() }

            cm.calls.any { it.startsWith("remove:") } shouldBe false
            cm.calls.any { it.startsWith("create:") } shouldBe false
            cm.calls.filter { it.startsWith("start:") } shouldBe listOf("start:craftpanel-srv-1")
            cm.gate.shouldReportDie("srv-1") shouldBe true
        }

        test("legacy graceful stop suppresses the die event it causes and reports STOPPED") {
            val cm = FakeContainerManager()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking { loop.applyLegacyStop("srv-1", "craftpanel-srv-1", 10, "", force = false).join() }

            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.STOPPED
            cm.gate.shouldReportDie("srv-1") shouldBe false
            channel.statuses()
                .last().status shouldBe ServerStatusUpdate.ServerStatus.STOPPED
        }

        test("legacy forced stop (kill) still suppresses the die event") {
            val cm = FakeContainerManager()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking { loop.applyLegacyStop("srv-1", "craftpanel-srv-1", 10, "", force = true).join() }

            cm.calls.any { it.startsWith("kill:") } shouldBe true
            cm.gate.shouldReportDie("srv-1") shouldBe false
            channel.statuses()
                .last().status shouldBe ServerStatusUpdate.ServerStatus.STOPPED
        }

        test("legacy force stop on already stopped container reports STOPPED not UNHEALTHY") {
            val cm = FakeContainerManager()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking { loop.applyLegacyStop("srv-1", "craftpanel-srv-1", 10, "", force = false).join() }
            runBlocking { loop.applyLegacyStop("srv-1", "craftpanel-srv-1", 10, "", force = true).join() }

            channel.statuses()
                .last().status shouldBe ServerStatusUpdate.ServerStatus.STOPPED
        }

        test("legacy failed stop emits UNHEALTHY and stays suppressed for the crash report") {
            val cm = FakeContainerManager()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            cm.failStop = true
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking { loop.applyLegacyStop("srv-1", "craftpanel-srv-1", 10, "", force = false).join() }

            channel.statuses()
                .last().status shouldBe ServerStatusUpdate.ServerStatus.UNHEALTHY
            cm.gate.shouldReportDie("srv-1") shouldBe false
        }

        test("legacy restart stops then starts and re-enables crash reporting") {
            val cm = FakeContainerManager()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            cm.calls.clear()
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking { loop.applyLegacyRestart("srv-1", "craftpanel-srv-1", 10, "").join() }

            cm.calls.filter { it.startsWith("stop:") || it.startsWith("start:") } shouldBe
                listOf("stop:craftpanel-srv-1", "start:craftpanel-srv-1")
            cm.gate.shouldReportDie("srv-1") shouldBe true
            channel.statuses()
                .last().status shouldBe ServerStatusUpdate.ServerStatus.HEALTHY
        }

        // ── desired-state envelope convergence ────────────────────────────────

        test("applyDesired RUNNING with a spec provisions a missing container") {
            val cm = FakeContainerManager()
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking { loop.applyDesired(desiredRunning(spec = startCmd())).join() }

            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.RUNNING
            channel.statuses()
                .last().status shouldBe ServerStatusUpdate.ServerStatus.HEALTHY
        }

        test("applyDesired STOPPED stops a running container and reports STOPPED") {
            val cm = FakeContainerManager()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking {
                loop.applyDesired(
                    serverDesiredState {
                        serverId = "srv-1"
                        desired = ServerDesiredState.Desired.STOPPED
                    }
                ).join()
            }

            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.STOPPED
            channel.statuses()
                .last().status shouldBe ServerStatusUpdate.ServerStatus.STOPPED
        }

        test("applyDesired STOPPED on an already-stopped container is a no-op reaffirm") {
            val cm = FakeContainerManager()
            cm.createContainer(startCmd())
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking {
                loop.applyDesired(
                    serverDesiredState {
                        serverId = "srv-1"
                        desired = ServerDesiredState.Desired.STOPPED
                    }
                ).join()
            }

            cm.calls.any { it.startsWith("stop:") || it.startsWith("kill:") } shouldBe false
            channel.statuses()
                .last().status shouldBe ServerStatusUpdate.ServerStatus.STOPPED
        }

        test("applyDesired RUNNING on a running container is a no-op and reaffirms HEALTHY") {
            val cm = FakeContainerManager()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            cm.calls.clear()
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking { loop.applyDesired(desiredRunning(spec = startCmd())).join() }

            cm.calls.any { it.startsWith("start:") } shouldBe false
            channel.statuses()
                .last().status shouldBe ServerStatusUpdate.ServerStatus.HEALTHY
        }

        test("applyDesired RUNNING recreates a stopped container when the pushed spec differs") {
            val cm = FakeContainerManager()
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking {
                loop.applyDesired(desiredRunning(spec = startCmd(image = "img-a"))).join()
                loop.applyDesired(
                    serverDesiredState {
                        serverId = "srv-1"
                        desired = ServerDesiredState.Desired.STOPPED
                    }
                ).join()
                cm.calls.clear()
                loop.applyDesired(desiredRunning(spec = startCmd(image = "img-b"))).join()
            }

            cm.calls.filter { it.startsWith("remove:") || it.startsWith("create:") || it.startsWith("start:") } shouldBe
                listOf("remove:craftpanel-srv-1", "create:craftpanel-srv-1", "start:craftpanel-srv-1")
            channel.statuses()
                .last().status shouldBe ServerStatusUpdate.ServerStatus.HEALTHY
        }

        test("applyDesired RUNNING re-push of the same spec does not recreate") {
            val cm = FakeContainerManager()
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking {
                loop.applyDesired(desiredRunning(spec = startCmd(image = "img-a"))).join()
                loop.applyDesired(
                    serverDesiredState {
                        serverId = "srv-1"
                        desired = ServerDesiredState.Desired.STOPPED
                    }
                ).join()
                cm.calls.clear()
                loop.applyDesired(desiredRunning(spec = startCmd(image = "img-a"))).join()
            }

            cm.calls.any { it.startsWith("remove:") || it.startsWith("create:") } shouldBe false
            cm.calls.filter { it.startsWith("start:") } shouldBe listOf("start:craftpanel-srv-1")
        }

        // ── crash-restart convergence ─────────────────────────────────────────

        fun runningFake(): FakeContainerManager {
            val cm = FakeContainerManager()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            return cm
        }

        test("an unexpected container death restarts the server within the budget") {
            val cm = runningFake()
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking {
                loop.applyDesired(desiredRunning(spec = startCmd())).join()
                // kill the container and mark it as stopped (simulating unexpected death after initial start)
                cm.gate.markStopping("srv-1")
                cm.killContainer("craftpanel-srv-1")
                loop.onContainerDie("srv-1", 1).join()
            }

            channel.statuses()
                .last().status shouldBe ServerStatusUpdate.ServerStatus.HEALTHY
            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.RUNNING
        }

        test("budget exhaustion reports CRASH_LOOPED and stops restarting") {
            // Use a mockk that always fails on start to simulate repeated crashes.
            // The budget only counts when start doesn't succeed (markHealthy is skipped on failure).
            val cm = mockk<ContainerManager>(relaxed = true) {
                every { containerExists(any()) } returns true
                every { isRunning(any()) } returns false
                every { startContainer(any()) } throws RuntimeException("crash-loop")
            }
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking {
                loop.applyDesired(desiredRunning(spec = startCmd())).join()
                // Each die event increments the budget counter because start fails each time.
                repeat(4) {
                    loop.onContainerDie("srv-1", 1).join()
                }
            }

            channel.statuses()
                .last().status shouldBe ServerStatusUpdate.ServerStatus.CRASH_LOOPED
        }

        test("no_restart suppresses crash-restart while desired remains RUNNING") {
            val cm = runningFake()
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)
            val env = serverDesiredState {
                serverId = "srv-1"
                desired = ServerDesiredState.Desired.RUNNING
                spec = startCmd()
                noRestart = true
            }

            runBlocking {
                loop.applyDesired(env).join()
                // Container dies; the die event should NOT trigger a restart under no_restart.
                cm.gate.markStopping("srv-1")
                cm.killContainer("craftpanel-srv-1")
                cm.calls.clear()
                loop.onContainerDie("srv-1", 1).join()
            }

            // Should not have attempted a restart — container remains stopped
            cm.calls.any { it.startsWith("start:") } shouldBe false
            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.STOPPED
        }

        // ── handler routing ───────────────────────────────────────────────────

        test("DesiredStateHandler forwards envelopes to the loop converge path") {
            val cm = FakeContainerManager()
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)
            val handler = DesiredStateHandler(loop)

            runBlocking {
                handler.handleDesiredState(desiredRunning(spec = startCmd()))
            }
            awaitStatus(channel, ServerStatusUpdate.ServerStatus.HEALTHY)

            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.RUNNING
        }
    })