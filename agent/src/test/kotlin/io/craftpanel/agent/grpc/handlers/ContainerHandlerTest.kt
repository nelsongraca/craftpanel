package io.craftpanel.agent.grpc.handlers

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.FakeContainerManager
import io.craftpanel.agent.docker.NetworkManager
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.proto.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import java.nio.file.Files

class ContainerHandlerTest :
    FunSpec({

        val symlinkTempRoot = Files.createTempDirectory("container-handler-test").toFile()
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

        fun newFake() = FakeContainerManager()

        fun newHandler(cm: FakeContainerManager) =
            ContainerHandler(cm, config, mockk<NetworkManager>(relaxed = true))

        fun startCmd(needsRecreate: Boolean = false, serverId: String = "srv-1") = startContainerCommand {
            containerName = "craftpanel-$serverId"
            this.serverId = serverId
            serverName = "myserver"
            image = "itzg/minecraft-server:latest"
            hostPort = 25565
            internalListenPort = 25565
            this.needsRecreate = needsRecreate
        }

        fun stopCmd(serverId: String = "srv-1") = stopContainerCommand {
            containerName = "craftpanel-$serverId"
            this.serverId = serverId
            timeoutSeconds = 10
            stopCommand = ""
        }

        fun restartCmd(serverId: String = "srv-1") = restartContainerCommand {
            containerName = "craftpanel-$serverId"
            this.serverId = serverId
            timeoutSeconds = 10
            stopCommand = ""
        }

        fun removeCmd(serverId: String = "srv-1") = removeContainerCommand {
            containerName = "craftpanel-$serverId"
            this.serverId = serverId
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

        test("start on missing container pulls, creates, starts and reports HEALTHY") {
            val cm = newFake()
            val handler = newHandler(cm)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)

            handler.handleStart(startCmd(), AgentOutbound(channel, "node-1"))

            cm.calls.filter { it.startsWith("pull:") || it.startsWith("create:") || it.startsWith("start:") } shouldBe
                listOf("pull:itzg/minecraft-server:latest", "create:craftpanel-srv-1", "start:craftpanel-srv-1")
            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.RUNNING
            cm.gate.shouldReportDie("srv-1") shouldBe true
            channel.statuses().single().status shouldBe ServerStatusUpdate.ServerStatus.HEALTHY
        }

        test("start with needsRecreate removes the old container first") {
            val cm = newFake()
            cm.createContainer(startCmd().toBuilder().clearNeedsRecreate().build())
            cm.startContainer("craftpanel-srv-1")
            cm.calls.clear()
            val handler = newHandler(cm)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)

            handler.handleStart(startCmd(needsRecreate = true), AgentOutbound(channel, "node-1"))

            cm.calls.filter { it.startsWith("remove:") || it.startsWith("create:") || it.startsWith("start:") } shouldBe
                listOf("remove:craftpanel-srv-1", "create:craftpanel-srv-1", "start:craftpanel-srv-1")
            cm.gate.shouldReportDie("srv-1") shouldBe true
            channel.statuses().single().status shouldBe ServerStatusUpdate.ServerStatus.HEALTHY
        }

        test("start without needsRecreate on existing container skips the create path") {
            val cm = newFake()
            cm.createContainer(startCmd())
            cm.calls.clear()
            val handler = newHandler(cm)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)

            handler.handleStart(startCmd(), AgentOutbound(channel, "node-1"))

            cm.calls.any { it.startsWith("remove:") } shouldBe false
            cm.calls.any { it.startsWith("create:") } shouldBe false
            cm.calls.filter { it.startsWith("start:") } shouldBe listOf("start:craftpanel-srv-1")
            cm.gate.shouldReportDie("srv-1") shouldBe true
        }

        test("graceful stop suppresses the die event it causes") {
            val cm = newFake()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            val handler = newHandler(cm)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)

            handler.handleStop(stopCmd(), AgentOutbound(channel, "node-1"))

            cm.containers["craftpanel-srv-1"]?.state shouldBe FakeContainerManager.State.STOPPED
            cm.gate.shouldReportDie("srv-1") shouldBe false
            channel.statuses().single().status shouldBe ServerStatusUpdate.ServerStatus.STOPPED
        }

        test("forced stop (kill) also suppresses the die event") {
            val cm = newFake()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            val handler = newHandler(cm)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)
            val cmd = stopCmd().toBuilder().setForce(true).build()

            handler.handleStop(cmd, AgentOutbound(channel, "node-1"))

            cm.calls.any { it.startsWith("kill:") } shouldBe true
            cm.gate.shouldReportDie("srv-1") shouldBe false
            channel.statuses().single().status shouldBe ServerStatusUpdate.ServerStatus.STOPPED
        }

        test("failed stop emits UNHEALTHY and stays suppressed for the crash report") {
            val cm = newFake()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            cm.failStop = true
            val handler = newHandler(cm)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)

            handler.handleStop(stopCmd(), AgentOutbound(channel, "node-1"))

            channel.statuses().single().status shouldBe ServerStatusUpdate.ServerStatus.UNHEALTHY
            cm.gate.shouldReportDie("srv-1") shouldBe false
        }

        test("restart stops then starts and re-enables crash reporting") {
            val cm = newFake()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            cm.calls.clear()
            val handler = newHandler(cm)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)

            handler.handleRestart(restartCmd(), AgentOutbound(channel, "node-1"))

            cm.calls.filter { it.startsWith("stop:") || it.startsWith("start:") } shouldBe
                listOf("stop:craftpanel-srv-1", "start:craftpanel-srv-1")
            cm.gate.shouldReportDie("srv-1") shouldBe true
            channel.statuses().single().status shouldBe ServerStatusUpdate.ServerStatus.HEALTHY
        }

        test("remove deletes the container and stops crash reporting") {
            val cm = newFake()
            cm.createContainer(startCmd())
            cm.startContainer("craftpanel-srv-1")
            val handler = newHandler(cm)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)

            handler.handleRemove(removeCmd(), AgentOutbound(channel, "node-1"))

            cm.containers.containsKey("craftpanel-srv-1") shouldBe false
            cm.gate.shouldReportDie("srv-1") shouldBe false
            channel.statuses().single().status shouldBe ServerStatusUpdate.ServerStatus.STOPPED
        }

        test("shutdown stops all managed containers and reports counts") {
            val cm = newFake()
            cm.createContainer(startCmd(serverId = "srv-1"))
            cm.createContainer(startCmd(serverId = "srv-2"))
            cm.startContainer("craftpanel-srv-1")
            cm.startContainer("craftpanel-srv-2")
            val handler = newHandler(cm)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)

            handler.handleShutdown(shutdownCommand { timeoutSeconds = 5 }, AgentOutbound(channel, "node-1"))

            val msgs = buildList {
                while (true) {
                    val r = channel.tryReceive()
                    if (r.isSuccess) add(r.getOrThrow()) else break
                }
            }
            val ack = msgs.first { it.hasShutdownAcknowledge() }.shutdownAcknowledge
            ack.gracefulCount shouldBe 2
            ack.forcedCount shouldBe 0
        }
    })
