package io.craftpanel.agent.grpc

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.*
import io.craftpanel.agent.grpc.handlers.*
import io.craftpanel.proto.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

class ControlStreamHandlerTest :
    FunSpec({
        val containerManager: ContainerManager = mockk(relaxed = true)
        val metricsCollector: MetricsCollector = mockk(relaxed = true)
        val identity = NodeIdentity(nodeId = "node-1", nodeKey = "test-key")
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
            dataBasePath = "",
            hostDataBasePath = "",
            serversByNameRoot = "",
            backupsByServerRoot = "",
            mcRouterImage = "itzg/mc-router:latest",
            mcRouterUpdateOnStart = false,
            publicIpUrl = "",
            hostnameOverride = "",
            systemReservedRamMb = 0,
            craftpanelNetwork = "craftpanel",
            containerNamePrefix = "craftpanel",
            metricsPollIntervalSeconds = 60,
            masterHttpPort = 80,
            privateIpOverride = "",
            mcRouterContainerName = ""
        )
        val containerHandler = ContainerHandler(containerManager, config, mockk<NetworkManager>(relaxed = true))
        val backupHandler = BackupHandler(config)
        val routerSupervisor = RouterSupervisor(mockk<McRouterProvisioner>(relaxed = true))
        val eventWatcher = ContainerEventWatcher(mockk(relaxed = true))
        val rsyncMigrator = RsyncMigrator(mockk(relaxed = true), config.craftpanelNetwork, config.containerNamePrefix)
        val consoleHandler = ConsoleHandler(containerManager)
        val handler = ControlStreamHandler(
            identity,
            config,
            containerManager,
            metricsCollector,
            routerSupervisor,
            containerHandler,
            eventWatcher,
            backupHandler,
            rsyncMigrator,
            console = consoleHandler
        )

        var tempDir: File = File("")

        beforeTest {
            tempDir = Files.createTempDirectory("handler-test")
                .toFile()
        }

        afterTest {
            tempDir.deleteRecursively()
        }

        var outboundChannel: Channel<AgentMessage> = Channel(Channel.UNLIMITED)
        var outbound: AgentOutbound

        fun newOutbound(): AgentOutbound {
            outboundChannel = Channel(Channel.UNLIMITED)
            outbound = AgentOutbound(outboundChannel, identity.nodeId)
            return outbound
        }

        fun Channel<AgentMessage>.messages(): List<AgentMessage> = buildList {
            while (true) {
                val r = tryReceive()
                if (r.isSuccess) add(r.getOrThrow()) else break
            }
        }

        // buildStateSnapshot
        test("buildStateSnapshot includes containers from manager") {
            every { containerManager.listContainers() } returns listOf(
                containerState {
                    serverId = "srv-1"
                    containerId = "c1"
                    runState = ContainerState.RunState.RUNNING
                }
            )

            val snapshot = handler.buildStateSnapshot()

            snapshot.containersCount shouldBe 1
            snapshot.containersList[0].serverId shouldBe "srv-1"
        }

        test("buildStateSnapshot returns empty snapshot when no containers") {
            every { containerManager.listContainers() } returns emptyList()

            handler.buildStateSnapshot().containersCount shouldBe 0
        }

        // handleStart
        test("handleStart emits HEALTHY on success") {
            runBlocking {
                every { containerManager.containerExists(any()) } returns true
                every { containerManager.startContainer(any()) } just Runs
                val outbound = newOutbound()

                containerHandler.handleStart(
                    startContainerCommand {
                        serverId = "srv-start"
                        containerName = "craftpanel-start"
                        needsRecreate = false
                    },
                    outbound
                )

                val msg = outboundChannel.messages()
                    .single()
                msg.serverStatus.status shouldBe ServerStatusUpdate.ServerStatus.HEALTHY
                msg.serverStatus.serverId shouldBe "srv-start"
            }
        }

        test("handleStart creates a servers-by-name symlink pointing at the server's canonical data dir") {
            runBlocking {
                every { containerManager.containerExists(any()) } returns true
                every { containerManager.startContainer(any()) } just Runs
                val byNameRoot = Files.createTempDirectory("by-name").toFile()
                val dataConfig = config.copy(
                    dataBasePath = tempDir.absolutePath,
                    serversByNameRoot = byNameRoot.absolutePath
                )
                val handlerWithData = ContainerHandler(containerManager, dataConfig, mockk<NetworkManager>(relaxed = true))
                val outbound = newOutbound()
                val serverId = "srv-symlink"
                val srvName = "survival-world"

                handlerWithData.handleStart(
                    startContainerCommand {
                        this.serverId = serverId
                        containerName = "craftpanel-$serverId"
                        needsRecreate = false
                        serverName = srvName
                    },
                    outbound
                )

                val link = java.nio.file.Path.of(byNameRoot.absolutePath, srvName)
                java.nio.file.Files.exists(link) shouldBe true
                java.nio.file.Files.isSymbolicLink(link) shouldBe true
                byNameRoot.deleteRecursively()
            }
        }

        test("handleStart emits UNHEALTHY on failure") {
            runBlocking {
                every { containerManager.containerExists(any()) } returns true
                every { containerManager.startContainer(any()) } throws RuntimeException("start failed")
                val outbound = newOutbound()

                containerHandler.handleStart(
                    startContainerCommand {
                        serverId = "srv-start-fail"
                        containerName = "craftpanel-start-fail"
                        needsRecreate = false
                    },
                    outbound
                )

                outboundChannel.messages()
                    .single().serverStatus.status shouldBe
                    ServerStatusUpdate.ServerStatus.UNHEALTHY
            }
        }

        test("handleStart with needsRecreate pulls image and recreates container") {
            runBlocking {
                every { containerManager.containerExists(any()) } returns true
                every { containerManager.removeContainer(any(), any()) } just Runs
                every { containerManager.pullImage(any()) } just Runs
                every { containerManager.createContainer(any()) } returns "new-id"
                every { containerManager.startContainer(any()) } just Runs
                val outbound = newOutbound()

                containerHandler.handleStart(
                    startContainerCommand {
                        serverId = "srv-recreate"
                        containerName = "craftpanel-recreate"
                        image = "itzg/minecraft-server:latest"
                        needsRecreate = true
                    },
                    outbound
                )

                verify { containerManager.pullImage("itzg/minecraft-server:latest") }
                verify { containerManager.removeContainer("craftpanel-recreate", force = true) }
                outboundChannel.messages()
                    .single().serverStatus.status shouldBe
                    ServerStatusUpdate.ServerStatus.HEALTHY
            }
        }

        test("handleStart mount uses /server for proxy data_container_path") {
            runBlocking {
                every { containerManager.containerExists(any()) } returns true
                every { containerManager.removeContainer(any(), any()) } just Runs
                every { containerManager.pullImage(any()) } just Runs
                val captured = slot<StartContainerCommand>()
                every { containerManager.createContainer(capture(captured)) } returns "new-id"
                every { containerManager.startContainer(any()) } just Runs
                val outbound = newOutbound()

                containerHandler.handleStart(
                    startContainerCommand {
                        serverId = "srv-proxy"
                        containerName = "craftpanel-proxy"
                        image = "itzg/mc-proxy:latest"
                        needsRecreate = true
                        dataContainerPath = "/server"
                    },
                    outbound
                )

                captured.captured.mountsList.single().containerPath shouldBe "/server"
            }
        }

        test("handleStart mount defaults to /data when data_container_path is empty") {
            runBlocking {
                every { containerManager.containerExists(any()) } returns true
                every { containerManager.removeContainer(any(), any()) } just Runs
                every { containerManager.pullImage(any()) } just Runs
                val captured = slot<StartContainerCommand>()
                every { containerManager.createContainer(capture(captured)) } returns "new-id"
                every { containerManager.startContainer(any()) } just Runs
                val outbound = newOutbound()

                containerHandler.handleStart(
                    startContainerCommand {
                        serverId = "srv-default"
                        containerName = "craftpanel-default"
                        image = "itzg/minecraft-server:latest"
                        needsRecreate = true
                    },
                    outbound
                )

                captured.captured.mountsList.single().containerPath shouldBe "/data"
            }
        }

        // handleStop
        test("handleStop emits STOPPED on success") {
            runBlocking {
                every { containerManager.stopContainer(any(), any(), any()) } just Runs
                val outbound = newOutbound()

                containerHandler.handleStop(
                    stopContainerCommand {
                        serverId = "srv-stop"
                        containerName = "craftpanel-stop"
                        timeoutSeconds = 10
                    },
                    outbound
                )

                val msg = outboundChannel.messages()
                    .single()
                msg.serverStatus.status shouldBe ServerStatusUpdate.ServerStatus.STOPPED
                msg.serverStatus.serverId shouldBe "srv-stop"
            }
        }

        test("handleStop emits UNHEALTHY on failure") {
            runBlocking {
                every { containerManager.stopContainer(any(), any(), any()) } throws RuntimeException("stop failed")
                val outbound = newOutbound()

                containerHandler.handleStop(
                    stopContainerCommand {
                        serverId = "srv-stop-fail"
                        containerName = "craftpanel-stop-fail"
                    },
                    outbound
                )

                outboundChannel.messages()
                    .single().serverStatus.status shouldBe
                    ServerStatusUpdate.ServerStatus.UNHEALTHY
            }
        }

        // handleRestart
        test("handleRestart emits HEALTHY after stop then start") {
            runBlocking {
                every { containerManager.stopContainer(any(), any(), any()) } just Runs
                every { containerManager.startContainer(any()) } just Runs
                val outbound = newOutbound()

                containerHandler.handleRestart(
                    restartContainerCommand {
                        serverId = "srv-restart"
                        containerName = "craftpanel-restart"
                        timeoutSeconds = 10
                    },
                    outbound
                )

                outboundChannel.messages()
                    .single().serverStatus.status shouldBe
                    ServerStatusUpdate.ServerStatus.HEALTHY
                verify { containerManager.stopContainer("craftpanel-restart", 10, "") }
                verify { containerManager.startContainer("craftpanel-restart") }
            }
        }

        test("handleRestart emits UNHEALTHY when stop fails") {
            runBlocking {
                every { containerManager.stopContainer(any(), any(), any()) } throws RuntimeException("stop failed")
                val outbound = newOutbound()

                containerHandler.handleRestart(
                    restartContainerCommand {
                        serverId = "srv-restart-fail"
                        containerName = "craftpanel-restart-fail"
                    },
                    outbound
                )

                outboundChannel.messages()
                    .single().serverStatus.status shouldBe
                    ServerStatusUpdate.ServerStatus.UNHEALTHY
            }
        }

        // handleRemove
        test("handleRemove calls containerManager removeContainer and emits STOPPED") {
            runBlocking {
                every { containerManager.removeContainer(any(), any()) } just Runs
                val outbound = newOutbound()

                containerHandler.handleRemove(
                    removeContainerCommand {
                        serverId = "srv-remove"
                        containerName = "craftpanel-remove"
                        force = true
                    },
                    outbound
                )

                verify { containerManager.removeContainer("craftpanel-remove", true) }
                outboundChannel.messages()
                    .single().serverStatus.status shouldBe
                    ServerStatusUpdate.ServerStatus.STOPPED
            }
        }

        test("handleRemove with deleteData=true removes the servers-by-name symlink") {
            runBlocking {
                every { containerManager.removeContainer(any(), any()) } just Runs
                val byNameRoot = Files.createTempDirectory("by-name-rm").toFile()
                val dataConfig = config.copy(
                    dataBasePath = tempDir.absolutePath,
                    serversByNameRoot = byNameRoot.absolutePath
                )
                val handlerWithData = ContainerHandler(containerManager, dataConfig, mockk<NetworkManager>(relaxed = true))
                val serverId = "srv-remove-symlink"
                val srvName = "removable-world"
                val serverDir = File(tempDir, "servers/$serverId").apply { mkdirs() }
                File(serverDir, "server.properties").writeText("motd=hi")
                SymlinkMaintainer.createServerNameSymlink(byNameRoot.absolutePath, srvName, java.nio.file.Path.of(serverDir.absolutePath))
                java.nio.file.Files.exists(java.nio.file.Path.of(byNameRoot.absolutePath, srvName)) shouldBe true
                val outbound = newOutbound()

                handlerWithData.handleRemove(
                    removeContainerCommand {
                        this.serverId = serverId
                        containerName = "craftpanel-$serverId"
                        force = true
                        deleteData = true
                        serverName = srvName
                    },
                    outbound
                )

                java.nio.file.Files.exists(java.nio.file.Path.of(byNameRoot.absolutePath, srvName)) shouldBe false
                byNameRoot.deleteRecursively()
            }
        }

        test("handleRemove emits UNHEALTHY on docker error") {
            runBlocking {
                every { containerManager.removeContainer(any(), any()) } throws RuntimeException("rm failed")
                val outbound = newOutbound()

                containerHandler.handleRemove(
                    removeContainerCommand {
                        serverId = "srv-rm-fail"
                        containerName = "craftpanel-rm-fail"
                        force = false
                    },
                    outbound
                )

                outboundChannel.messages()
                    .single().serverStatus.status shouldBe
                    ServerStatusUpdate.ServerStatus.UNHEALTHY
            }
        }

        test("handleRemove with deleteData=true deletes the server's data directory") {
            runBlocking {
                every { containerManager.removeContainer(any(), any()) } just Runs
                val dataConfig = config.copy(dataBasePath = tempDir.absolutePath)
                val handlerWithData = ContainerHandler(containerManager, dataConfig, mockk<NetworkManager>(relaxed = true))
                val serverId = "srv-delete-data"
                val serverDir = File(tempDir, "servers/$serverId").apply { mkdirs() }
                File(serverDir, "server.properties").writeText("motd=hi")
                val outbound = newOutbound()

                handlerWithData.handleRemove(
                    removeContainerCommand {
                        this.serverId = serverId
                        containerName = "craftpanel-$serverId"
                        force = true
                        deleteData = true
                    },
                    outbound
                )

                serverDir.exists() shouldBe false
                outboundChannel.messages()
                    .single().serverStatus.status shouldBe
                    ServerStatusUpdate.ServerStatus.STOPPED
            }
        }

        test("handleRemove with deleteData=false leaves the server's data directory intact") {
            runBlocking {
                every { containerManager.removeContainer(any(), any()) } just Runs
                val dataConfig = config.copy(dataBasePath = tempDir.absolutePath)
                val handlerWithData = ContainerHandler(containerManager, dataConfig, mockk<NetworkManager>(relaxed = true))
                val serverId = "srv-keep-data"
                val serverDir = File(tempDir, "servers/$serverId").apply { mkdirs() }
                File(serverDir, "server.properties").writeText("motd=hi")
                val outbound = newOutbound()

                handlerWithData.handleRemove(
                    removeContainerCommand {
                        this.serverId = serverId
                        containerName = "craftpanel-$serverId"
                        force = true
                        deleteData = false
                    },
                    outbound
                )

                serverDir.exists() shouldBe true
            }
        }

        // handleShutdown
        test("handleShutdown emits shutdownAcknowledge with container counts") {
            runBlocking {
                every { containerManager.shutdownAll(any()) } returns Pair(3, 1)
                val outbound = newOutbound()

                containerHandler.handleShutdown(shutdownCommand { timeoutSeconds = 30 }, outbound)

                val msg = outboundChannel.messages()
                    .single()
                msg.hasShutdownAcknowledge()
                    .shouldBeTrue()
                msg.shutdownAcknowledge.gracefulCount shouldBe 3
                msg.shutdownAcknowledge.forcedCount shouldBe 1
            }
        }

        // handleDeleteBackup
        test("handleDeleteBackup deletes the file") {
            runBlocking {
                val file = File(tempDir, "backup.tar.gz").also { it.writeText("dummy") }

                backupHandler.handleDeleteBackup(deleteBackupCommand { filePath = file.absolutePath })

                file.exists() shouldBe false
            }
        }

        test("handleDeleteBackup does not throw when file does not exist") {
            runBlocking {
                backupHandler.handleDeleteBackup(deleteBackupCommand { filePath = "/nonexistent/backup.tar.gz" })
            }
        }

        // handleTriggerBackup
        test("handleTriggerBackup emits failure when server data dir does not exist") {
            runBlocking {
                val outbound = newOutbound()

                backupHandler.handleTriggerBackup(
                    triggerBackupCommand {
                        backupId = "bk-1"
                        serverId = "srv-bk"
                        containerName = "craftpanel-mc"
                    },
                    outbound
                )

                val messages = outboundChannel.messages()
                val complete = messages.last { it.hasBackupComplete() }
                complete.backupComplete.backupId shouldBe "bk-1"
                complete.backupComplete.success shouldBe false
                complete.backupComplete.errorMessage.isNotEmpty() shouldBe true
            }
        }

        test("handleTriggerBackup emits progress and success for valid source directory") {
            runBlocking {
                val serverId = "srv-bk-2"
                File(tempDir, "servers/$serverId").also { it.mkdirs() }
                    .let { File(it, "world").writeText("level data") }
                val outbound = newOutbound()

                BackupHandler(config.copy(dataBasePath = tempDir.absolutePath)).handleTriggerBackup(
                    triggerBackupCommand {
                        backupId = "bk-2"
                        this.serverId = serverId
                        containerName = "craftpanel-mc"
                    },
                    outbound
                )

                val messages = outboundChannel.messages()
                messages.any { it.hasBackupProgress() } shouldBe true
                messages.any { it.hasBackupComplete() } shouldBe true
                messages.last { it.hasBackupComplete() }.backupComplete.success shouldBe true
                File(tempDir, "backups/bk-2.tar.gz").exists() shouldBe true
            }
        }

        test("handleTriggerBackup creates a date-based backups-by-server symlink") {
            runBlocking {
                val serverId = "srv-bk-3"
                val backupName = "survival-world"
                val timestamp = "2026-07-18_14-30-00"
                File(tempDir, "servers/$serverId").also { it.mkdirs() }
                    .let { File(it, "world").writeText("level data") }
                val byServerRoot = Files.createTempDirectory("by-server").toFile()
                val outbound = newOutbound()

                BackupHandler(config.copy(dataBasePath = tempDir.absolutePath, backupsByServerRoot = byServerRoot.absolutePath)).handleTriggerBackup(
                    triggerBackupCommand {
                        backupId = "bk-3"
                        this.serverId = serverId
                        containerName = "craftpanel-mc"
                        serverName = backupName
                        createdAtFormatted = timestamp
                    },
                    outbound
                )

                val link = java.nio.file.Path.of(byServerRoot.absolutePath, backupName, "$timestamp.tar.gz")
                java.nio.file.Files.exists(link) shouldBe true
                java.nio.file.Files.isSymbolicLink(link) shouldBe true
                byServerRoot.deleteRecursively()
            }
        }

        test("handleDeleteBackup removes the backups-by-server symlink") {
            runBlocking {
                val backupName = "removable-server"
                val timestamp = "2026-07-18_15-00-00"
                val byServerRoot = Files.createTempDirectory("by-server-rm").toFile()
                val realBackup = File(tempDir, "backups/bk-rm.tar.gz").apply {
                    parentFile.mkdirs()
                    writeText("backup data")
                }
                SymlinkMaintainer.createBackupSymlink(byServerRoot.absolutePath, backupName, timestamp, java.nio.file.Path.of(realBackup.absolutePath))
                java.nio.file.Files.exists(java.nio.file.Path.of(byServerRoot.absolutePath, backupName, "$timestamp.tar.gz")) shouldBe true

                BackupHandler(config.copy(backupsByServerRoot = byServerRoot.absolutePath)).handleDeleteBackup(
                    deleteBackupCommand {
                        backupId = "bk-rm"
                        filePath = realBackup.absolutePath
                        serverName = backupName
                        createdAtFormatted = timestamp
                    }
                )

                java.nio.file.Files.exists(java.nio.file.Path.of(byServerRoot.absolutePath, backupName, "$timestamp.tar.gz")) shouldBe false
                byServerRoot.deleteRecursively()
            }
        }
    })
