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

        val symlinkTempRoot = Files.createTempDirectory("container-handler-test")
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
                }
                else break
            }
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
            channel.statuses()
                .single().status shouldBe ServerStatusUpdate.ServerStatus.STOPPED
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
