package io.craftpanel.agent.grpc

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.*
import io.craftpanel.agent.grpc.handlers.*
import io.craftpanel.proto.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

class ControlStreamHandlerTest :
    FunSpec({
        val containerManager: ContainerManager = mockk(relaxed = true)
        val metricsCollector: MetricsCollector = mockk(relaxed = true)
        val identity = NodeIdentity(nodeId = "node-1", nodeKey = "test-key")
        val symlinkTempRoot = Files.createTempDirectory("agent-symlinks")
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
            dataBasePath = "",
            hostDataBasePath = "",
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
        val containerHandler = ContainerHandler(containerManager, config, mockk<NetworkManager>(relaxed = true))
        val backupHandler = BackupHandler(config)
        val routerSupervisor = RouterSupervisor(mockk<McRouterProvisioner>(relaxed = true))
        val eventWatcher = ContainerEventWatcher(mockk(relaxed = true))
        val consoleHandler = ConsoleHandler(mockk(relaxed = true), mockk(relaxed = true))
        // Shared test view: the convergence loop is a mock here because these tests exercise the
        // handler's snapshot/remove/shutdown/symlink paths only; convergence behaviour is covered in
        // ConvergenceLoopTest.
        val handler = ControlStreamHandler(
            identity,
            config,
            containerManager,
            metricsCollector,
            routerSupervisor,
            eventWatcher,
            CommandDispatcher(
                container = containerHandler,
                desired = mockk(relaxed = true),
                backup = backupHandler,
                migration = mockk(relaxed = true),
                file = mockk(relaxed = true),
                console = consoleHandler,
                bulkClient = mockk(relaxed = true)
            ),
            gate = WatcherGate(),
            out = mockk(relaxed = true),
            loop = mockk(relaxed = true),
            convergenceScope = CoroutineScope(Job())
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
                val byNameRoot = Files.createTempDirectory("by-name-rm")
                    .toFile()
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
                Files.exists(java.nio.file.Path.of(byNameRoot.absolutePath, srvName)) shouldBe true
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

                Files.exists(java.nio.file.Path.of(byNameRoot.absolutePath, srvName)) shouldBe false
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
                val byServerRoot = Files.createTempDirectory("by-server")
                    .toFile()
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
                Files.exists(link) shouldBe true
                Files.isSymbolicLink(link) shouldBe true
                byServerRoot.deleteRecursively()
            }
        }

        test("handleDeleteBackup removes the backups-by-server symlink") {
            runBlocking {
                val backupName = "removable-server"
                val timestamp = "2026-07-18_15-00-00"
                val byServerRoot = Files.createTempDirectory("by-server-rm")
                    .toFile()
                val realBackup = File(tempDir, "backups/bk-rm.tar.gz").apply {
                    parentFile.mkdirs()
                    writeText("backup data")
                }
                SymlinkMaintainer.createBackupSymlink(byServerRoot.absolutePath, backupName, timestamp, java.nio.file.Path.of(realBackup.absolutePath))
                Files.exists(java.nio.file.Path.of(byServerRoot.absolutePath, backupName, "$timestamp.tar.gz")) shouldBe true

                BackupHandler(config.copy(backupsByServerRoot = byServerRoot.absolutePath)).handleDeleteBackup(
                    deleteBackupCommand {
                        backupId = "bk-rm"
                        filePath = realBackup.absolutePath
                        serverName = backupName
                        createdAtFormatted = timestamp
                    }
                )

                Files.exists(java.nio.file.Path.of(byServerRoot.absolutePath, backupName, "$timestamp.tar.gz")) shouldBe false
                byServerRoot.deleteRecursively()
            }
        }

        test("rebuildServerSymlinks recreates servers-by-name symlinks from snapshot") {
            runBlocking {
                val serverId = "srv-rebuild"
                val serverName = "rebuilt-world"
                val byNameRoot = Files.createTempDirectory("by-name-rebuild")
                    .toFile()
                val serverDir = File(tempDir, "servers/$serverId").apply { mkdirs() }

                val b = RebuildSymlinksCommand.newBuilder()
                b.addServersBuilder()
                    .setServerId(serverId)
                    .setServerName(serverName)
                val cmd = b.build()

                // Stale primary link + a folded leftover from the old collision logic.
                Files.createSymbolicLink(
                    java.nio.file.Path.of(byNameRoot.absolutePath, serverName),
                    java.nio.file.Path.of("../../servers/stale-dir")
                )
                Files.createSymbolicLink(
                    java.nio.file.Path.of(byNameRoot.absolutePath, "$serverName-deadbeef"),
                    java.nio.file.Path.of("../../servers/stale-dir")
                )

                val rebuildConfig = config.copy(
                    dataBasePath = tempDir.absolutePath,
                    serversByNameRoot = byNameRoot.absolutePath
                )

                ContainerHandler(containerManager, rebuildConfig, mockk<NetworkManager>(relaxed = true))
                    .rebuildServerSymlinks(cmd.serversList)

                val primary = java.nio.file.Path.of(byNameRoot.absolutePath, serverName)
                Files.isSymbolicLink(primary) shouldBe true
                primary.toRealPath() shouldBe serverDir.toPath().toRealPath()
                Files.exists(java.nio.file.Path.of(byNameRoot.absolutePath, "$serverName-deadbeef")) shouldBe false
                byNameRoot.deleteRecursively()
            }
        }

        test("rebuildBackupSymlinks recreates backups-by-server symlinks from snapshot") {
            runBlocking {
                val serverId = "srv-rebuild"
                val serverName = "rebuilt-world"
                val byServerRoot = Files.createTempDirectory("by-server-rebuild")
                    .toFile()
                val serverDir = File(tempDir, "servers/$serverId").apply { mkdirs() }
                val backupFile = File(tempDir, "backups/bk-rebuild.tar.gz").apply {
                    parentFile.mkdirs()
                    writeText("backup")
                }
                val timestamp = "2026-07-18_16-00-00"

                val b = RebuildSymlinksCommand.newBuilder()
                b.addBackupsBuilder()
                    .setBackupId("bk-rebuild")
                    .setServerId(serverId)
                    .setServerName(serverName)
                    .setCreatedAtFormatted(timestamp)
                    .setFilePath(backupFile.absolutePath)
                val cmd = b.build()

                // Stale backup link that no longer appears in the snapshot.
                Files.createDirectories(java.nio.file.Path.of(byServerRoot.absolutePath, serverName))
                Files.createSymbolicLink(
                    java.nio.file.Path.of(byServerRoot.absolutePath, serverName, "1999-01-01_00-00-00.tar.gz"),
                    java.nio.file.Path.of("../../../backups/gone.tar.gz")
                )

                val rebuildConfig = config.copy(
                    dataBasePath = tempDir.absolutePath,
                    serversByNameRoot = byServerRoot.absolutePath,
                    backupsByServerRoot = byServerRoot.absolutePath
                )

                BackupHandler(rebuildConfig).rebuildBackupSymlinks(cmd.backupsList)

                Files.exists(java.nio.file.Path.of(byServerRoot.absolutePath, serverName, "$timestamp.tar.gz")) shouldBe true
                Files.exists(java.nio.file.Path.of(byServerRoot.absolutePath, serverName, "1999-01-01_00-00-00.tar.gz")) shouldBe false
                byServerRoot.deleteRecursively()
            }
        }
    })
