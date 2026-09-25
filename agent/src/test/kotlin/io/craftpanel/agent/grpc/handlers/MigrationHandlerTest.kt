package io.craftpanel.agent.grpc.handlers

import com.github.dockerjava.api.async.ResultCallback
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.RsyncMigrator
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.proto.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class MigrationHandlerTest :
    FunSpec({

        val root = Files.createTempDirectory("migration-handler-test")
        val config = AgentConfig(
            profile = "dev",
            masterAddress = "localhost",
            masterPort = 50051,
            masterHttpPort = 8080,
            tlsCertPath = "",
            caCertFilePath = root.resolve("ca.pem").toString(),
            bootstrapToken = "bootstrap-token-16",
            keyFilePath = root.resolve("node.key").toString(),
            dockerSocketPath = "unix:///var/run/docker.sock",
            dataBasePath = root.toString(),
            hostDataBasePath = root.toString(),
            serversByNameRoot = root.resolve("servers-by-name").toString(),
            backupsByServerRoot = root.resolve("backups-by-server").toString(),
            mcRouterImage = "router:latest",
            mcRouterUpdateOnStart = false,
            mcRouterContainerName = "",
            publicIpUrl = "",
            hostnameOverride = "",
            systemReservedRamMb = 0,
            systemReservedCpuMillicores = 0,
            craftpanelNetwork = "craftpanel",
            containerNamePrefix = "craftpanel",
            privateIpOverride = "192.0.2.10"
        )

        afterSpec { root.toFile().deleteRecursively() }

        fun outbound() = Channel<AgentMessage>(Channel.UNLIMITED).let { it to AgentOutbound(it, "node-1") }

        test("prepare receiver pulls the requested image and emits a ready update") {
            val containers = mockk<ContainerManager>(relaxed = true)
            val migrator = mockk<RsyncMigrator>(relaxed = true)
            val (channel, out) = outbound()
            val handler = MigrationHandler(config, containers, migrator)

            runBlocking {
                handler.handlePrepareRsyncReceive(
                    prepareRsyncReceiveCommand {
                        migrationId = "migration-1"
                        serverId = "srv-1"
                        port = 873
                        rsyncImage = "rsync:latest"
                    },
                    out
                )
            }

            verify { containers.pullImage("rsync:latest") }
            verify { migrator.startReceiver("migration-1", 873, any(), any(), "rsync:latest") }
            channel.tryReceive().getOrThrow().rsyncReady.migrationId shouldBe "migration-1"
        }

        test("prepare receiver does not emit ready when startup fails") {
            val containers = mockk<ContainerManager>(relaxed = true)
            val migrator = mockk<RsyncMigrator>()
            every { migrator.startReceiver(any(), any(), any(), any(), any()) } throws IllegalStateException("boom")
            val (channel, out) = outbound()

            runBlocking {
                MigrationHandler(config, containers, migrator).handlePrepareRsyncReceive(
                    prepareRsyncReceiveCommand {
                        migrationId = "migration-2"
                        serverId = "srv-1"
                    },
                    out
                )
            }

            channel.tryReceive().isFailure shouldBe true
        }

        test("start rsync forwards progress and reports a failed transfer") {
            val containers = mockk<ContainerManager>(relaxed = true)
            val migrator = mockk<RsyncMigrator>()
            every {
                migrator.runTransfer(any(), any(), any(), any(), any(), any(), any(), any())
            } answers {
                lastArg<(Long, Long, Int, String) -> Unit>()(10, 100, 10, "sending")
                false
            }
            val (channel, out) = outbound()

            runBlocking {
                MigrationHandler(config, containers, migrator).handleStartRsync(
                    startRsyncCommand {
                        migrationId = "migration-3"
                        serverId = "srv-1"
                        destinationIp = "192.0.2.20"
                        destinationPort = 873
                        rsyncPassword = "secret"
                        isFinalPass = true
                    },
                    out
                )
            }

            val messages = buildList {
                while (true) {
                    val result = channel.tryReceive()
                    if (result.isSuccess) add(result.getOrThrow()) else break
                }
            }
            messages.any { it.hasRsyncProgress() && it.rsyncProgress.percentComplete == 10 } shouldBe true
            messages.single { it.hasRsyncComplete() }.rsyncComplete.errorCode shouldBe ErrorCode.INTERNAL
        }

        test("start rsync reports transfer exceptions") {
            val containers = mockk<ContainerManager>(relaxed = true)
            val migrator = mockk<RsyncMigrator>()
            every { migrator.runTransfer(any(), any(), any(), any(), any(), any(), any(), any()) } throws
                IllegalStateException("transfer failed")
            val (channel, out) = outbound()

            runBlocking {
                MigrationHandler(config, containers, migrator).handleStartRsync(
                    startRsyncCommand {
                        migrationId = "migration-4"
                        serverId = "srv-1"
                    },
                    out
                )
            }

            channel.tryReceive().getOrThrow().rsyncComplete.errorMessage shouldBe "transfer failed"
        }

        test("send RCON delegates to the container manager") {
            val containers = mockk<ContainerManager>(relaxed = true)

            MigrationHandler(config, containers, mockk(relaxed = true)).handleSendRcon(
                sendRconCommand {
                    serverId = "srv-1"
                    command = "save-all"
                }
            )

            verify { containers.execRconCommand("srv-1", "save-all") }
        }
    })
