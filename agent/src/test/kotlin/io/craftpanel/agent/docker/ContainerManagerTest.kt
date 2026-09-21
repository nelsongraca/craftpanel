package io.craftpanel.agent.docker

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.*
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.model.*
import io.craftpanel.proto.ContainerState
import io.craftpanel.proto.startContainerCommand
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*

class ContainerManagerTest :
    FunSpec({
        val docker: DockerClient = mockk()
        val manager = DockerContainerManager(docker, WatcherGate())
        val objectMapper = ObjectMapper()

        // The shared `docker` mock records calls across tests; clear between runs so
        // count/absence verifications (exactly 0, exactly N) stay order-independent.
        beforeTest { clearMocks(docker) }

        fun fakeContainer(
            name: String,
            state: String,
            status: String,
            id: String = "container-id",
            serverId: String? = "server-id",
            stopCommand: String? = null,
            routingHost: String? = null
        ): Container {
            val labelsJson = buildString {
                append("{")
                if (serverId != null) {
                    append(""""craftpanel.managed":"true","craftpanel.server.id":"$serverId"""")
                    if (stopCommand != null) append(""","craftpanel.stop.command":"$stopCommand"""")
                    if (routingHost != null) append(""","mc-router.host":"$routingHost"""")
                }
                append("}")
            }
            val json = """{"Id":"$id","Names":["$name"],"State":"$state","Status":"$status","Labels":$labelsJson}"""
            return objectMapper.readValue(json, Container::class.java)
        }

        fun stubListAll(containers: List<Container>) {
            val cmd = mockk<ListContainersCmd>()
            every { docker.listContainersCmd() } returns cmd
            every { cmd.withShowAll(true) } returns cmd
            every { cmd.exec() } returns containers
        }

        fun stubListRunning(containers: List<Container>) {
            val cmd = mockk<ListContainersCmd>()
            every { docker.listContainersCmd() } returns cmd
            every { cmd.withShowAll(false) } returns cmd
            every { cmd.exec() } returns containers
        }

        fun stubCreate(returnedId: String): CreateContainerCmd {
            val createCmd = mockk<CreateContainerCmd>(relaxed = true)
            every { docker.createContainerCmd(any()) } returns createCmd
            every { createCmd.withName(any()) } returns createCmd
            every { createCmd.withEnv(any<List<String>>()) } returns createCmd
            every { createCmd.withExposedPorts(any<List<ExposedPort>>()) } returns createCmd
            every { createCmd.withHostConfig(any()) } returns createCmd
            every { createCmd.withLabels(any()) } returns createCmd
            every { createCmd.withStdinOpen(any()) } returns createCmd
            every { createCmd.withHostName(any()) } returns createCmd
            val response = mockk<CreateContainerResponse>()
            every { response.id } returns returnedId
            every { createCmd.exec() } returns response
            return createCmd
        }

        fun stubStop(containerId: String) {
            val stopCmd = mockk<StopContainerCmd>(relaxed = true)
            every { docker.stopContainerCmd(containerId) } returns stopCmd
            every { stopCmd.withTimeout(any()) } returns stopCmd
        }

        fun stubKillWithSignal(): KillContainerCmd {
            val killCmd = mockk<KillContainerCmd>(relaxed = true)
            every { docker.killContainerCmd("craftpanel-mc") } returns killCmd
            every { killCmd.withSignal(any()) } returns killCmd
            return killCmd
        }

        fun stubInspect(running: Boolean) {
            val inspectCmd = mockk<InspectContainerCmd>(relaxed = true)
            every { docker.inspectContainerCmd("craftpanel-mc") } returns inspectCmd
            val inspectResponse = mockk<InspectContainerResponse>(relaxed = true)
            every { inspectCmd.exec() } returns inspectResponse
            val state = mockk<InspectContainerResponse.ContainerState>(relaxed = true)
            every { inspectResponse.state } returns state
            every { state.running } returns running
        }

        // listContainers
        test("listContainers maps running state to RUNNING") {
            stubListAll(listOf(fakeContainer(name = "/craftpanel-test", state = "running", status = "Up 2 hours")))

            val result = manager.listContainers()

            result.size shouldBe 1
            result[0].runState shouldBe ContainerState.RunState.RUNNING
        }

        test("listContainers maps exited with code 0 to STOPPED") {
            stubListAll(listOf(fakeContainer(name = "/craftpanel-test", state = "exited", status = "Exited (0) 5 minutes ago")))

            manager.listContainers()[0].runState shouldBe ContainerState.RunState.STOPPED
        }

        test("listContainers maps non-zero exited to EXITED") {
            stubListAll(listOf(fakeContainer(name = "/craftpanel-test", state = "exited", status = "Exited (137) 1 hour ago")))

            manager.listContainers()[0].runState shouldBe ContainerState.RunState.EXITED
        }

        test("listContainers filters out containers without craftpanel- prefix") {
            stubListAll(
                listOf(
                    fakeContainer(name = "/craftpanel-web", state = "running", status = "Up"),
                    fakeContainer(name = "/nginx", state = "running", status = "Up")
                )
            )

            val result = manager.listContainers()

            result.size shouldBe 1
            result[0].containerName shouldBe "craftpanel-web"
        }

        test("listContainers sets serverId from label") {
            stubListAll(listOf(fakeContainer(name = "/craftpanel-abc", state = "running", status = "Up", serverId = "server-uuid-123")))

            manager.listContainers()[0].serverId shouldBe "server-uuid-123"
        }

        test("listContainers strips leading slash from container name") {
            stubListAll(listOf(fakeContainer(name = "/craftpanel-srv", state = "running", status = "Up")))

            manager.listContainers()[0].containerName shouldBe "craftpanel-srv"
        }

        // listRunningContainerIds
        test("listRunningContainerIds returns only containers with craftpanel server id label") {
            stubListRunning(
                listOf(
                    fakeContainer(name = "/craftpanel-mc", state = "running", status = "Up", serverId = "srv-1"),
                    fakeContainer(name = "/craftpanel-other", state = "running", status = "Up", serverId = null)
                )
            )

            val result = manager.listRunningContainerIds()

            result.size shouldBe 1
            result[0].first shouldBe "srv-1"
        }

        test("listRunningContainerIds returns empty list when no containers") {
            stubListRunning(emptyList())
            manager.listRunningContainerIds().size shouldBe 0
        }

        test("listRunningContainers carries the mc-router.host label as routingHost") {
            stubListRunning(
                listOf(
                    fakeContainer(
                        name = "/craftpanel-mc",
                        state = "running",
                        status = "Up",
                        serverId = "srv-1",
                        routingHost = "play.example.com"
                    )
                )
            )

            manager.listRunningContainers()
                .single().routingHost shouldBe "play.example.com"
        }

        test("listRunningContainers takes the first entry of a comma-separated mc-router.host label") {
            stubListRunning(
                listOf(
                    fakeContainer(
                        name = "/craftpanel-mc",
                        state = "running",
                        status = "Up",
                        serverId = "srv-1",
                        routingHost = "primary.example.com,secondary.example.com"
                    )
                )
            )

            manager.listRunningContainers()
                .single().routingHost shouldBe "primary.example.com"
        }

        test("listRunningContainers routingHost is null when the label is absent") {
            stubListRunning(listOf(fakeContainer(name = "/craftpanel-mc", state = "running", status = "Up", serverId = "srv-1")))

            manager.listRunningContainers()
                .single().routingHost shouldBe null
        }

        // createContainer
        test("createContainer returns container id from docker response") {
            stubCreate("new-container-id")

            val id = manager.createContainer(
                startContainerCommand {
                    containerName = "craftpanel-test"
                    serverId = "srv-1"
                    image = "itzg/minecraft-server:latest"
                    hostPort = 25565
                }
            )

            id shouldBe "new-container-id"
        }

        test("createContainer exposes and routes to internalListenPort for game servers") {
            val createCmd = stubCreate("c1")

            manager.createContainer(
                startContainerCommand {
                    containerName = "craftpanel-srv"
                    serverId = "srv-1"
                    image = "itzg/minecraft-server:latest"
                    hostPort = 25565
                    publicHostname = "mc.example.com"
                    internalListenPort = 25565
                }
            )

            verify { createCmd.withExposedPorts(listOf(ExposedPort.tcp(25565))) }
            verify {
                createCmd.withLabels(
                    mapOf(
                        "craftpanel.managed" to "true",
                        "craftpanel.server.id" to "srv-1",
                        "mc-router.host" to "mc.example.com",
                        "mc-router.port" to "25565"
                    )
                )
            }
        }

        test("createContainer exposes and routes to 25577 for proxy servers") {
            val createCmd = stubCreate("c1")

            manager.createContainer(
                startContainerCommand {
                    containerName = "craftpanel-proxy"
                    serverId = "srv-proxy"
                    image = "itzg/mc-proxy:latest"
                    hostPort = 25566
                    publicHostname = "proxy.example.com"
                    internalListenPort = 25577
                }
            )

            verify { createCmd.withExposedPorts(listOf(ExposedPort.tcp(25577))) }
            verify {
                createCmd.withLabels(
                    mapOf(
                        "craftpanel.managed" to "true",
                        "craftpanel.server.id" to "srv-proxy",
                        "mc-router.host" to "proxy.example.com",
                        "mc-router.port" to "25577"
                    )
                )
            }
        }

        test("createContainer uses a UDP exposed port and skips mc-router labels for UDP servers") {
            val createCmd = stubCreate("c1")

            manager.createContainer(
                startContainerCommand {
                    containerName = "craftpanel-custom"
                    serverId = "srv-udp"
                    image = "itzg/minecraft-server:latest"
                    hostPort = 25570
                    publicHostname = "custom.example.com"
                    internalListenPort = 25566
                    containerProtocol = "UDP"
                }
            )

            verify { createCmd.withExposedPorts(listOf(ExposedPort.udp(25566))) }
            verify {
                createCmd.withLabels(
                    mapOf(
                        "craftpanel.managed" to "true",
                        "craftpanel.server.id" to "srv-udp"
                    )
                )
            }
        }

        test("createContainer sets server id label") {
            val createCmd = stubCreate("c1")

            manager.createContainer(
                startContainerCommand {
                    containerName = "craftpanel-srv"
                    serverId = "srv-label-test"
                    image = "itzg/minecraft-server:latest"
                }
            )

            verify {
                createCmd.withLabels(
                    mapOf(
                        "craftpanel.managed" to "true",
                        "craftpanel.server.id" to "srv-label-test"
                    )
                )
            }
        }

        test("createContainer stores stop.command label when stopCommand is set") {
            val createCmd = stubCreate("c1")

            manager.createContainer(
                startContainerCommand {
                    containerName = "craftpanel-srv"
                    serverId = "srv-1"
                    image = "itzg/minecraft-server:latest"
                    stopCommand = "stop"
                }
            )

            verify {
                createCmd.withLabels(
                    mapOf(
                        "craftpanel.managed" to "true",
                        "craftpanel.server.id" to "srv-1",
                        "craftpanel.stop.command" to "stop"
                    )
                )
            }
        }

        test("createContainer does not store stop.command label when stopCommand is empty") {
            val createCmd = stubCreate("c1")

            manager.createContainer(
                startContainerCommand {
                    containerName = "craftpanel-srv"
                    serverId = "srv-1"
                    image = "itzg/minecraft-server:latest"
                    stopCommand = ""
                }
            )

            verify {
                createCmd.withLabels(
                    mapOf(
                        "craftpanel.managed" to "true",
                        "craftpanel.server.id" to "srv-1"
                    )
                )
            }
        }

        // startContainer
        test("startContainer invokes docker start") {
            val startCmd = mockk<StartContainerCmd>(relaxed = true)
            every { docker.startContainerCmd("craftpanel-mc") } returns startCmd

            manager.startContainer("craftpanel-mc")

            verify { startCmd.exec() }
        }

        // stopContainer
        test("stopContainer calls docker stop with specified timeout") {
            val stopCmd = mockk<StopContainerCmd>(relaxed = true)
            every { docker.stopContainerCmd("craftpanel-mc") } returns stopCmd
            every { stopCmd.withTimeout(any()) } returns stopCmd

            manager.stopContainer("craftpanel-mc", timeoutSeconds = 15, stopCommand = "")

            verify { stopCmd.withTimeout(15) }
            verify { stopCmd.exec() }
        }

        test("stopContainer uses the shared default timeout when timeout is zero") {
            val stopCmd = mockk<StopContainerCmd>(relaxed = true)
            every { docker.stopContainerCmd("craftpanel-mc") } returns stopCmd
            every { stopCmd.withTimeout(any()) } returns stopCmd

            manager.stopContainer("craftpanel-mc", timeoutSeconds = 0, stopCommand = "")

            verify { stopCmd.withTimeout(ContainerManager.DEFAULT_STOP_TIMEOUT_SECONDS) }
        }

        // stopContainer — signal stop commands (^C and similar)
        test("stopContainer delivers SIGINT for ^C stop command") {
            val killCmd = stubKillWithSignal()
            stubInspect(running = false)

            manager.stopContainer("craftpanel-mc", timeoutSeconds = 30, stopCommand = "^C")

            verify { killCmd.withSignal("SIGINT") }
            verify { killCmd.exec() }
            verify(exactly = 0) { docker.attachContainerCmd(any()) }
            verify(exactly = 0) { docker.stopContainerCmd(any()) }
        }

        test("stopContainer delivers SIGQUIT for Ctrl-Backslash stop command") {
            val killCmd = stubKillWithSignal()
            stubInspect(running = false)

            manager.stopContainer("craftpanel-mc", timeoutSeconds = 30, stopCommand = "^\\")

            verify { killCmd.withSignal("SIGQUIT") }
        }

        test("stopContainer passes explicit SIGTERM signal through") {
            val killCmd = stubKillWithSignal()
            stubInspect(running = false)

            manager.stopContainer("craftpanel-mc", timeoutSeconds = 30, stopCommand = "SIGTERM")

            verify { killCmd.withSignal("SIGTERM") }
        }

        test("stopContainer falls back to docker stop when container still running after signal") {
            stubKillWithSignal()
            stubInspect(running = true)
            stubStop("craftpanel-mc")

            manager.stopContainer("craftpanel-mc", timeoutSeconds = 1, stopCommand = "^C")

            verify { docker.killContainerCmd("craftpanel-mc") }
            verify { docker.stopContainerCmd("craftpanel-mc") }
        }

        test("stopContainer writes text stop commands to stdin, never as signals") {
            val attachCmd = mockk<AttachContainerCmd>(relaxed = true)
            every { docker.attachContainerCmd("craftpanel-mc") } returns attachCmd
            every { attachCmd.withStdIn(any()) } returns attachCmd
            every { attachCmd.withStdOut(any()) } returns attachCmd
            every { attachCmd.withStdErr(any()) } returns attachCmd
            every { attachCmd.withFollowStream(any()) } returns attachCmd
            every { attachCmd.withLogs(any()) } returns attachCmd
            val adapter = ResultCallback.Adapter<Frame>()
            adapter.onComplete() // pre-complete so awaitCompletion returns immediately
            every { attachCmd.exec(any()) } returns adapter
            stubInspect(running = true)
            stubStop("craftpanel-mc")

            manager.stopContainer("craftpanel-mc", timeoutSeconds = 1, stopCommand = "stop")

            verify(exactly = 0) { docker.killContainerCmd(any()) }
            verify { docker.attachContainerCmd("craftpanel-mc") }
            verify { docker.stopContainerCmd("craftpanel-mc") }
        }

        // killContainer
        test("killContainer calls docker kill and is idempotent on missing container") {
            val killCmd = mockk<KillContainerCmd>(relaxed = true)
            every { docker.killContainerCmd("craftpanel-mc") } returns killCmd

            manager.killContainer("craftpanel-mc")

            verify { killCmd.exec() }
        }

        test("killContainer swallows NotFoundException") {
            every { docker.killContainerCmd("craftpanel-mc") } throws NotFoundException("no such container")
            shouldNotThrow<NotFoundException> { manager.killContainer("craftpanel-mc") }
        }

        // removeContainer
        test("removeContainer calls docker remove with force flag") {
            val removeCmd = mockk<RemoveContainerCmd>(relaxed = true)
            every { docker.removeContainerCmd("craftpanel-mc") } returns removeCmd
            every { removeCmd.withForce(true) } returns removeCmd

            manager.removeContainer("craftpanel-mc", force = true)

            verify { removeCmd.withForce(true) }
            verify { removeCmd.exec() }
        }

        // shutdownAll
        test("shutdownAll counts graceful stops") {
            stubListRunning(
                listOf(
                    fakeContainer(name = "/craftpanel-a", state = "running", status = "Up", id = "c1"),
                    fakeContainer(name = "/craftpanel-b", state = "running", status = "Up", id = "c2")
                )
            )
            stubStop("craftpanel-a")
            stubStop("craftpanel-b")

            val (graceful, forced) = manager.shutdownAll(10)

            graceful shouldBe 2
            forced shouldBe 0
        }

        test("shutdownAll counts forced kills when graceful stop fails") {
            stubListRunning(listOf(fakeContainer(name = "/craftpanel-a", state = "running", status = "Up", id = "c1")))

            val stopCmd = mockk<StopContainerCmd>(relaxed = true)
            every { docker.stopContainerCmd("craftpanel-a") } returns stopCmd
            every { stopCmd.withTimeout(any()) } returns stopCmd
            every { stopCmd.exec() } throws RuntimeException("stop failed")

            val killCmd = mockk<KillContainerCmd>(relaxed = true)
            every { docker.killContainerCmd("c1") } returns killCmd

            val (graceful, forced) = manager.shutdownAll(10)

            graceful shouldBe 0
            forced shouldBe 1
        }

        test("shutdownAll returns zeros when no craftpanel containers running") {
            stubListRunning(emptyList())

            val (graceful, forced) = manager.shutdownAll(10)

            graceful shouldBe 0
            forced shouldBe 0
        }

        @Suppress("UNCHECKED_CAST")
        test("shutdownAll uses graceful stop with stop command from label") {
            stubListRunning(
                listOf(
                    fakeContainer(name = "/craftpanel-a", state = "running", status = "Up", id = "c1", stopCommand = "stop")
                )
            )

            val attachCmd = mockk<AttachContainerCmd>(relaxed = true)
            every { docker.attachContainerCmd("craftpanel-a") } returns attachCmd
            every { attachCmd.withStdIn(any()) } returns attachCmd
            every { attachCmd.withStdOut(any()) } returns attachCmd
            every { attachCmd.withStdErr(any()) } returns attachCmd
            every { attachCmd.withFollowStream(any()) } returns attachCmd
            every { attachCmd.withLogs(any()) } returns attachCmd
            val adapter = ResultCallback.Adapter<Frame>()
            adapter.onComplete() // pre-complete so awaitCompletion returns immediately
            every { attachCmd.exec(any()) } returns adapter

            val inspectCmd = mockk<InspectContainerCmd>(relaxed = true)
            every { docker.inspectContainerCmd("craftpanel-a") } returns inspectCmd
            val inspectResponse = mockk<InspectContainerResponse>(relaxed = true)
            every { inspectCmd.exec() } returns inspectResponse
            val state = mockk<InspectContainerResponse.ContainerState>(relaxed = true)
            every { inspectResponse.state } returns state
            every { state.running } returns false

            val (graceful, forced) = manager.shutdownAll(10)

            graceful shouldBe 1
            forced shouldBe 0
        }

        test("shutdownAll falls back to docker stop when stop command label is absent") {
            stubListRunning(
                listOf(
                    fakeContainer(name = "/craftpanel-a", state = "running", status = "Up", id = "c1")
                )
            )
            stubStop("craftpanel-a")

            val (graceful, forced) = manager.shutdownAll(10)

            graceful shouldBe 1
            forced shouldBe 0
        }

        // Covers the `docker inspect` -> ContainerSnapshot mapping the recreate-if-diff decision
        // depends on (env parsing, mounts, port bindings, memory bytes->MB, labels, network mode).
        test("inspectContainer maps a docker inspect response into a ContainerSnapshot") {
            val config = ContainerConfig()
                .withImage("itzg/minecraft-server:latest")
                .withUser("1000")
                .withEnv(arrayOf("MOTD=hi", "PVP=true"))
                .withLabels(mapOf("craftpanel.managed" to "true", "mc-router.host" to "play.example.com"))
            val hostConfig = HostConfig()
                .withMemory(1_073_741_824L)
                .withNanoCPUs(512_000_000L)
                .withNetworkMode("craftpanel-net-net-1")
                .withPortBindings(
                    Ports().also {
                        it.bind(ExposedPort.tcp(25565), Ports.Binding.bindPort(25566))
                        it.bind(ExposedPort.udp(19132), Ports.Binding.bindPort(19133))
                    }
                )
            val mount = InspectContainerResponse.Mount()
                .withSource("/host/servers/srv-1")
                .withDestination(Volume("/data"))
                .withRw(true)

            val inspectResponse = mockk<InspectContainerResponse>(relaxed = true)
            every { inspectResponse.config } returns config
            every { inspectResponse.hostConfig } returns hostConfig
            every { inspectResponse.mounts } returns listOf(mount)
            val inspectCmd = mockk<InspectContainerCmd>(relaxed = true)
            every { docker.inspectContainerCmd("craftpanel-srv-1") } returns inspectCmd
            every { inspectCmd.exec() } returns inspectResponse

            val snapshot = manager.inspectContainer("craftpanel-srv-1")!!

            snapshot.image shouldBe "itzg/minecraft-server:latest"
            snapshot.user shouldBe "1000"
            snapshot.env shouldBe mapOf("MOTD" to "hi", "PVP" to "true")
            snapshot.binds shouldBe listOf(BindSnapshot("/host/servers/srv-1", "/data", false))
            snapshot.portBindings.sortedBy { it.containerPort } shouldBe listOf(
                PortBindingSnapshot(19132, "udp", 19133),
                PortBindingSnapshot(25565, "tcp", 25566)
            )
            snapshot.memoryMb shouldBe 1024
            snapshot.cpuLimitMillicores shouldBe 512
            snapshot.labels shouldBe mapOf("craftpanel.managed" to "true", "mc-router.host" to "play.example.com")
            snapshot.networkMode shouldBe "craftpanel-net-net-1"
        }

        test("inspectContainer returns null when the container does not exist") {
            val inspectCmd = mockk<InspectContainerCmd>(relaxed = true)
            every { docker.inspectContainerCmd("missing") } returns inspectCmd
            every { inspectCmd.exec() } throws NotFoundException("no such container")

            manager.inspectContainer("missing") shouldBe null
        }
    })
