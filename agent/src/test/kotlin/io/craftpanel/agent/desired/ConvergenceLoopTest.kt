package io.craftpanel.agent.desired

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.FakeContainerManager
import io.craftpanel.agent.docker.NetworkManager
import io.craftpanel.agent.docker.WatcherGate
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.agent.grpc.handlers.DesiredStateHandler
import io.craftpanel.proto.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
            gate: WatcherGate = WatcherGate(),
            startConflictRetryDelayMs: Long = 5_000L,
            ensureRouterRunning: suspend () -> Unit = {}
        ) = ConvergenceLoop(
            store = store,
            operator = ContainerOperator(
                cm,
                mockk<NetworkManager>(relaxed = true),
                config,
                ensureRouterRunning = ensureRouterRunning,
                startConflictRetryDelayMs = startConflictRetryDelayMs
            ),
            containerNamePrefix = config.containerNamePrefix,
            gate = gate,
            out = outbound,
            scope = scope
        )

        fun startCmd(serverId: String = "srv-1", serverName: String = "myserver", image: String = "itzg/minecraft-server:latest", publicHostname: String = "") = startContainerCommand {
            containerName = "craftpanel-$serverId"
            this.serverId = serverId
            this.serverName = serverName
            this.image = image
            this.publicHostname = publicHostname
            hostPort = 25565
            internalListenPort = 25565
        }

        fun desiredRunning(serverId: String = "srv-1", spec: StartContainerCommand? = null) = serverDesiredState {
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
                } else {
                    break
                }
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

        test("applyDesired RUNNING for an exposed server ensures mc-router before starting the container") {
            val cm = FakeContainerManager()
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out, ensureRouterRunning = { cm.calls.add("ensure-router") })

            runBlocking {
                loop.applyDesired(desiredRunning(spec = startCmd(publicHostname = "play.example.com"))).join()
            }

            // Router is ensured before the container is created, so the mc-router.host label routes
            // the moment the server is up.
            cm.calls.filter { it == "ensure-router" || it.startsWith("create:") } shouldBe
                listOf("ensure-router", "create:craftpanel-srv-1")
            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.RUNNING
            channel.statuses().last().status shouldBe ServerStatusUpdate.ServerStatus.HEALTHY
        }

        test("applyDesired RUNNING for an unexposed server does not touch mc-router") {
            val cm = FakeContainerManager()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out, ensureRouterRunning = { cm.calls.add("ensure-router") })

            runBlocking { loop.applyDesired(desiredRunning(spec = startCmd())).join() }

            cm.calls.any { it == "ensure-router" } shouldBe false
            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.RUNNING
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

        test("a restart recreates when the live container config differs from the desired spec") {
            // The container was created with a different image than the desired spec. Even with an
            // unknown in-memory applied spec, the inspect proves the mismatch → recreate.
            val cm = FakeContainerManager()
            cm.createContainer(startCmd(image = "old-image"))
            cm.startContainer("craftpanel-srv-1")
            cm.calls.clear()
            val store = DesiredStateStore()
            store.upsert("srv-1") { it.copy(spec = startCmd(image = "new-image"), appliedSpec = null) }
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out, store = store)

            runBlocking {
                loop.applyDesired(
                    desiredRunning(spec = startCmd(image = "new-image")).toBuilder().setForceRestart(true).build()
                ).join()
            }

            cm.calls.filter { it.startsWith("remove:") || it.startsWith("create:") || it.startsWith("start:") } shouldBe
                listOf("remove:craftpanel-srv-1", "create:craftpanel-srv-1", "start:craftpanel-srv-1")
            channel.statuses().last().status shouldBe ServerStatusUpdate.ServerStatus.HEALTHY
        }

        test("a restart does not recreate when the live container config matches the desired spec") {
            val cm = FakeContainerManager()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out)
            val spec = startCmd()

            runBlocking {
                loop.applyDesired(desiredRunning(spec = spec)).join() // creates the container
                cm.calls.clear()
                loop.applyDesired(
                    desiredRunning(spec = spec).toBuilder().setForceRestart(true).build()
                ).join()
            }

            cm.calls.any { it.startsWith("remove:") || it.startsWith("create:") } shouldBe false
            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.RUNNING
        }

        test("changing the stop command does not force a recreate") {
            val cm = FakeContainerManager()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out)
            val base = startCmd()
            val withStop = base.toBuilder().setStopCommand("^C").build()

            runBlocking {
                loop.applyDesired(desiredRunning(spec = base)).join() // created with no stop command
                cm.calls.clear()
                loop.applyDesired(
                    desiredRunning(spec = withStop).toBuilder().setForceRestart(true).build()
                ).join()
            }

            cm.calls.any { it.startsWith("remove:") || it.startsWith("create:") } shouldBe false
            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.RUNNING
        }

        test("force stop preempts an in-flight graceful stop with SIGKILL") {
            val cm = FakeContainerManager()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking {
                loop.applyDesired(desiredRunning(spec = startCmd())).join()
                cm.calls.clear()
                cm.stopBlockMs = 3_000

                // Graceful stop enters stopContainer and blocks, holding the per-server mutex.
                val graceful = loop.applyDesired(
                    serverDesiredState {
                        serverId = "srv-1"
                        desired = ServerDesiredState.Desired.STOPPED
                    }
                )
                delay(300)
                cm.calls.count { it.startsWith("kill:") } shouldBe 0

                loop.applyDesired(
                    serverDesiredState {
                        serverId = "srv-1"
                        desired = ServerDesiredState.Desired.STOPPED
                        force = true
                    }
                )
                delay(300)
                cm.calls.count { it.startsWith("kill:") } shouldBe 1

                graceful.join()
            }
            cm.stopBlockMs = 0
        }

        // ── failure reporting + authored-death suppression + spec passthrough ──

        test("a failed start reports UNHEALTHY") {
            val cm = FakeContainerManager()
            cm.failStart = true
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking { loop.applyDesired(desiredRunning(spec = startCmd())).join() }

            channel.statuses().last().status shouldBe ServerStatusUpdate.ServerStatus.UNHEALTHY
        }

        test("a transient host-port conflict is retried until the port frees up") {
            val cm = FakeContainerManager()
            cm.startPortConflicts = 2
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out, startConflictRetryDelayMs = 1L)

            runBlocking { loop.applyDesired(desiredRunning(spec = startCmd())).join() }

            channel.statuses().last().status shouldBe ServerStatusUpdate.ServerStatus.HEALTHY
            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.RUNNING
        }

        test("a persistent host-port conflict still reports UNHEALTHY") {
            val cm = FakeContainerManager()
            cm.startPortConflicts = 100
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out, startConflictRetryDelayMs = 1L)

            runBlocking { loop.applyDesired(desiredRunning(spec = startCmd())).join() }

            channel.statuses().last().status shouldBe ServerStatusUpdate.ServerStatus.UNHEALTHY
        }

        test("a failed graceful stop reports UNHEALTHY") {
            val cm = FakeContainerManager()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            cm.failStop = true
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

            channel.statuses().last().status shouldBe ServerStatusUpdate.ServerStatus.UNHEALTHY
        }

        test("an authored graceful stop suppresses the die event for that death") {
            val cm = FakeContainerManager()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out)
            cm.gate.shouldReportDie("srv-1") shouldBe true // managed, healthy

            runBlocking {
                loop.applyDesired(
                    serverDesiredState {
                        serverId = "srv-1"
                        desired = ServerDesiredState.Desired.STOPPED
                    }
                ).join()
            }

            cm.gate.shouldReportDie("srv-1") shouldBe false
        }

        test("an authored force stop suppresses the die event for that death") {
            val cm = FakeContainerManager()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out)
            cm.gate.shouldReportDie("srv-1") shouldBe true

            runBlocking {
                loop.applyDesired(
                    serverDesiredState {
                        serverId = "srv-1"
                        desired = ServerDesiredState.Desired.STOPPED
                        force = true
                    }
                ).join()
            }

            cm.gate.shouldReportDie("srv-1") shouldBe false
        }

        test("a proxy spec's volume mounts the data container path /server") {
            val cm = FakeContainerManager()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out)
            val spec = startCmd().toBuilder()
                .setContainerName("craftpanel-srv-1")
                .setDataContainerPath("/server")
                .build()

            runBlocking { loop.applyDesired(desiredRunning(spec = spec)).join() }

            cm.createdCommands.single()
                .mountsList.single().containerPath shouldBe "/server"
        }

        test("a spec with no data container path mounts /data by default") {
            val cm = FakeContainerManager()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking { loop.applyDesired(desiredRunning(spec = startCmd())).join() }

            cm.createdCommands.single()
                .mountsList.single().containerPath shouldBe "/data"
        }

        test("a successful start creates the servers-by-name symlink to the canonical data dir") {
            val cm = FakeContainerManager()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out)
            val serverName = "world-alpha"

            runBlocking { loop.applyDesired(desiredRunning(spec = startCmd(serverName = serverName))).join() }

            val link = java.nio.file.Path.of(symlinkTempRoot.absolutePath, serverName)
            Files.exists(link) shouldBe true
            Files.isSymbolicLink(link) shouldBe true
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
                loop.onContainerDie("srv-1").join()
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
                    loop.onContainerDie("srv-1").join()
                }
            }

            channel.statuses()
                .last().status shouldBe ServerStatusUpdate.ServerStatus.CRASH_LOOPED
        }

        test("a crash loop where the container starts then immediately dies is reported as CRASH_LOOPED") {
            // Regression: markHealthy used to reset the budget on every successful launch, so a
            // container that starts and immediately crashes (exit 2) restarted forever and never
            // hit CRASH_LOOPED. maxAttempts is 3 in desiredRunning.
            val cm = FakeContainerManager()
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking {
                loop.applyDesired(desiredRunning(spec = startCmd())).join() // start #0 → HEALTHY
                repeat(5) {
                    cm.killContainer("craftpanel-srv-1")
                    loop.onContainerDie("srv-1").join()
                }
            }

            channel.statuses().last().status shouldBe ServerStatusUpdate.ServerStatus.CRASH_LOOPED
            // 1 initial start + 3 budgeted restarts; the 4th and 5th crashes are not restarted.
            cm.calls.count { it == "start:craftpanel-srv-1" } shouldBe 4
        }

        test("an explicit user restart resets the crash-restart budget") {
            val cm = FakeContainerManager()
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking {
                loop.applyDesired(desiredRunning(spec = startCmd())).join()
                // Exhaust the budget: 3 crashes restarted, the 4th is not.
                repeat(3) {
                    cm.killContainer("craftpanel-srv-1")
                    loop.onContainerDie("srv-1").join()
                }
                // A user restart clears the crash history ...
                loop.applyDesired(
                    desiredRunning(spec = startCmd()).toBuilder().setForceRestart(true).build()
                ).join()
                // ... so the next crash is restarted again rather than crash-looping instantly.
                cm.killContainer("craftpanel-srv-1")
                loop.onContainerDie("srv-1").join()
            }

            channel.statuses().last().status shouldBe ServerStatusUpdate.ServerStatus.HEALTHY
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
                loop.onContainerDie("srv-1").join()
            }

            // Should not have attempted a restart — container remains stopped
            cm.calls.any { it.startsWith("start:") } shouldBe false
            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.STOPPED
        }

        // ── ownership seeding + reconcile backstop ────────────────────────────

        test("applyDesired seeds gate ownership when the container is already running") {
            // Regression: after an agent process restart the gate is empty and the container is
            // already running, so convergence NoOps and `startContainer` never marks it managed.
            // Every later death was then suppressed. Master intent must seed ownership.
            val cm = runningFake()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out, gate = cm.gate)
            cm.gate.markRemoved("srv-1")
            cm.gate.shouldReportDie("srv-1") shouldBe false

            runBlocking { loop.applyDesired(desiredRunning(spec = startCmd())).join() }

            cm.gate.shouldReportDie("srv-1") shouldBe true
        }

        test("applyDesired RUNNING clears a stale stopping flag") {
            val cm = runningFake()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out, gate = cm.gate)
            cm.gate.markStopping("srv-1")
            cm.gate.shouldReportDie("srv-1") shouldBe false

            runBlocking { loop.applyDesired(desiredRunning(spec = startCmd())).join() }

            cm.gate.shouldReportDie("srv-1") shouldBe true
        }

        test("applyDesired STOPPED leaves the stopping flag set") {
            val cm = runningFake()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out, gate = cm.gate)
            cm.gate.markStopping("srv-1")

            runBlocking {
                loop.applyDesired(
                    serverDesiredState {
                        serverId = "srv-1"
                        desired = ServerDesiredState.Desired.STOPPED
                    }
                ).join()
            }

            cm.gate.shouldReportDie("srv-1") shouldBe false
        }

        test("reconcileAll restarts a container that died with no die event") {
            val cm = runningFake()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out)
            runBlocking { loop.applyDesired(desiredRunning(spec = startCmd())).join() }
            // Death with no die event delivered: the container stops out-of-band, gate untouched.
            cm.containers["craftpanel-srv-1"]!!.state = FakeContainerManager.State.STOPPED
            cm.calls.clear()

            runBlocking { loop.reconcileAll() }

            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.RUNNING
            cm.calls.any { it == "start:craftpanel-srv-1" } shouldBe true
        }

        test("reconcileAll leaves running containers untouched") {
            val cm = runningFake()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out)
            runBlocking { loop.applyDesired(desiredRunning(spec = startCmd())).join() }
            cm.calls.clear()

            runBlocking { loop.reconcileAll() }

            cm.calls.any { it.startsWith("start:") || it.startsWith("create:") } shouldBe false
        }

        test("reconcileAll does not restart a server whose intent is STOPPED") {
            val cm = runningFake()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out)
            runBlocking {
                loop.applyDesired(desiredRunning(spec = startCmd())).join()
                loop.applyDesired(
                    serverDesiredState {
                        serverId = "srv-1"
                        desired = ServerDesiredState.Desired.STOPPED
                    }
                ).join()
            }
            cm.calls.clear()

            runBlocking { loop.reconcileAll() }

            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.STOPPED
            cm.calls.any { it.startsWith("start:") } shouldBe false
        }

        test("an exit-0 die event restarts under desired RUNNING") {
            // Exit code is informational only; intent decides. A graceful self-exit while master
            // still wants the server up must be restarted.
            val cm = runningFake()
            val (channel, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking {
                loop.applyDesired(desiredRunning(spec = startCmd())).join()
                cm.gate.markStopping("srv-1")
                cm.killContainer("craftpanel-srv-1")
                loop.onContainerDie("srv-1").join()
            }

            channel.statuses().last().status shouldBe ServerStatusUpdate.ServerStatus.HEALTHY
            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.RUNNING
        }

        test("an exit-0 die event does not restart under desired STOPPED") {
            val cm = runningFake()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking {
                loop.applyDesired(
                    serverDesiredState {
                        serverId = "srv-1"
                        desired = ServerDesiredState.Desired.STOPPED
                    }
                ).join()
            }
            cm.calls.clear()

            runBlocking { loop.onContainerDie("srv-1").join() }

            cm.calls.any { it.startsWith("start:") } shouldBe false
        }

        test("converge clears a stale stopping flag when the container is observed running") {
            val cm = runningFake()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out, gate = cm.gate)
            runBlocking { loop.applyDesired(desiredRunning(spec = startCmd())).join() }
            cm.gate.markStopping("srv-1")
            cm.gate.shouldReportDie("srv-1") shouldBe false

            runBlocking { loop.onContainerDie("srv-1").join() }

            cm.gate.shouldReportDie("srv-1") shouldBe true
        }

        test("a concurrent restart envelope is not clobbered by an in-flight converge") {
            // Regression for the ServerDataDirOverrideTest flake: a plain RUNNING converge was
            // holding the per-server lock between its read and its write; the restart envelope
            // upserted force_restart=true, then the in-flight converge wrote its stale snapshot
            // (force_restart=false) back over it, so the container was never restarted.
            val cm = runningFake()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out)
            runBlocking { loop.applyDesired(desiredRunning(spec = startCmd())).join() }

            val inspectEntered = CountDownLatch(1)
            val releaseInspect = CountDownLatch(1)
            cm.beforeInspect = {
                inspectEntered.countDown()
                releaseInspect.await()
            }

            val plain = loop.applyDesired(desiredRunning(spec = startCmd()))
            inspectEntered.await(5, TimeUnit.SECONDS) shouldBe true

            val restart = loop.applyDesired(
                desiredRunning(spec = startCmd()).toBuilder()
                    .setForceRestart(true)
                    .build()
            )

            cm.beforeInspect = null
            releaseInspect.countDown()
            runBlocking {
                plain.join()
                restart.join()
            }

            cm.calls.any { it.startsWith("stop:") } shouldBe true
            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.RUNNING
        }

        // ── CPU limit lookup (metrics normalization) ─────────────────────────

        test("cpuLimitMillicores returns 0 for an unknown server") {
            val cm = FakeContainerManager()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out)

            loop.cpuLimitMillicores("srv-unknown") shouldBe 0
        }

        test("cpuLimitMillicores falls back to the desired spec when nothing is applied") {
            val cm = FakeContainerManager()
            val (_, out) = newOutbound()
            val store = DesiredStateStore()
            val loop = newLoop(cm, out, store = store)

            store.upsert("srv-1") {
                it.copy(spec = startCmd().toBuilder().setCpuLimitMillicores(1500).build())
            }

            loop.cpuLimitMillicores("srv-1") shouldBe 1500
        }

        test("cpuLimitMillicores prefers the applied spec over the desired spec") {
            val cm = FakeContainerManager()
            val (_, out) = newOutbound()
            val store = DesiredStateStore()
            val loop = newLoop(cm, out, store = store)

            store.upsert("srv-1") {
                it.copy(
                    spec = startCmd().toBuilder().setCpuLimitMillicores(2000).build(),
                    appliedSpec = startCmd().toBuilder().setCpuLimitMillicores(1000).build()
                )
            }

            loop.cpuLimitMillicores("srv-1") shouldBe 1000
        }

        // ── JVM metrics policy lookup ─────────────────────────────────────────

        test("jvmMetricsPolicy is null for a server this agent does not own") {
            val cm = FakeContainerManager()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out)

            loop.jvmMetricsPolicy("srv-unknown") shouldBe null
        }

        test("jvmMetricsPolicy is read live from the desired-state envelope") {
            val cm = FakeContainerManager()
            val (_, out) = newOutbound()
            val loop = newLoop(cm, out)

            runBlocking {
                loop.applyDesired(
                    desiredRunning(spec = startCmd()).toBuilder()
                        .setJvmMetrics(
                            jvmMetricsPolicy {
                                enabled = false
                                pollIntervalSeconds = 45
                            }
                        )
                        .build()
                ).join()
            }

            loop.jvmMetricsPolicy("srv-1") shouldBe JvmMetricsPolicy(enabled = false, pollIntervalSeconds = 45)
        }

        test("changing the JVM policy does not alter the container spec (no recreate)") {
            val cm = FakeContainerManager()
            val (_, out) = newOutbound()
            val store = DesiredStateStore()
            val loop = newLoop(cm, out, store = store)

            runBlocking {
                loop.applyDesired(
                    desiredRunning(spec = startCmd()).toBuilder()
                        .setJvmMetrics(
                            jvmMetricsPolicy {
                                enabled = true
                                pollIntervalSeconds = 30
                            }
                        )
                        .build()
                ).join()
            }
            val specBefore = store.get("srv-1").spec

            runBlocking {
                loop.applyDesired(
                    desiredRunning(spec = startCmd()).toBuilder()
                        .setJvmMetrics(
                            jvmMetricsPolicy {
                                enabled = false
                                pollIntervalSeconds = 60
                            }
                        )
                        .build()
                ).join()
            }

            // The policy lives on the envelope, not the spec, so the spec is untouched.
            store.get("srv-1").spec shouldBe specBefore
            loop.jvmMetricsPolicy("srv-1") shouldBe JvmMetricsPolicy(enabled = false, pollIntervalSeconds = 60)
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
