package io.craftpanel.agent.grpc

import io.craftpanel.proto.*
import com.github.dockerjava.api.DockerClient
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.MetricsCollector
import io.mockk.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ControlStreamHandlerTest {

    private val containerManager: ContainerManager = mockk()
    private val metricsCollector: MetricsCollector = mockk(relaxed = true)
    private val docker: DockerClient = mockk(relaxed = true)
    private val identity = NodeIdentity(nodeId = "node-1", nodeKey = "test-key")
    private val config = AgentConfig(
        profile = "dev",
        masterAddress = "localhost",
        masterPort = 50051,
        tlsCertPath = "",
        caCertFilePath = "/etc/craftpanel/grpc-ca.crt",
        bootstrapToken = "test-token-16chars",
        keyFilePath = "/etc/craftpanel/node.key",
        dockerSocketPath = "unix:///var/run/docker.sock",
        agentVersion = "test",
        dataBasePath = "",  // overridden per test via tempDir
        hostDataBasePath = "",
        mcRouterImage = "itzg/mc-router:latest",
        mcRouterUpdateOnStart = false,
        publicIpUrl = "",
        hostnameOverride = "",
        systemReservedRamMb = 0,
        craftpanelNetwork = "craftpanel",
        containerNamePrefix = "craftpanel",
        metricsPollIntervalSeconds = 60,
    )
    private val handler = ControlStreamHandler(identity, config, containerManager, metricsCollector, docker)

    private lateinit var tempDir: File

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("handler-test")
            .toFile()
    }

    @AfterTest
    fun teardown() {
        tempDir.deleteRecursively()
    }

    // -------------------------------------------------------------------------
    // buildStateSnapshot
    // -------------------------------------------------------------------------

    @Test
    fun `buildStateSnapshot includes containers from manager`() {
        every { containerManager.listContainers() } returns listOf(
            containerState {
                serverId = "srv-1"
                containerId = "c1"
                runState = ContainerState.RunState.RUNNING
            }
        )

        val snapshot = handler.buildStateSnapshot()

        assertEquals(1, snapshot.containersCount)
        assertEquals("srv-1", snapshot.containersList[0].serverId)
    }

    @Test
    fun `buildStateSnapshot returns empty snapshot when no containers`() {
        every { containerManager.listContainers() } returns emptyList()

        assertEquals(0, handler.buildStateSnapshot().containersCount)
    }

    // -------------------------------------------------------------------------
    // handleCreate
    // -------------------------------------------------------------------------

    @Test
    fun `handleCreate emits STOPPED status on success`() = runBlocking {
        every { containerManager.pullImage(any()) } just Runs
        every { containerManager.createContainer(any()) } returns "new-container-id"
        val outbound = channel()

        handler.handleCreate(createContainerCommand {
            serverId = "srv-create"
            containerName = "craftpanel-create"
            image = "itzg/minecraft-server:latest"
        }, outbound)

        val msg = outbound.messages()
            .single()
        assertTrue(msg.hasServerStatus())
        assertEquals("srv-create", msg.serverStatus.serverId)
        assertEquals(ServerStatusUpdate.ServerStatus.STOPPED, msg.serverStatus.status)
        assertEquals("new-container-id", msg.serverStatus.containerId)
    }

    @Test
    fun `handleCreate emits nothing on failure`() = runBlocking {
        every { containerManager.createContainer(any()) } throws RuntimeException("docker error")
        val outbound = channel()

        handler.handleCreate(createContainerCommand {
            serverId = "srv-fail"
            containerName = "craftpanel-fail"
            image = "itzg/minecraft-server:latest"
        }, outbound)

        assertTrue(
            outbound.messages()
                .isEmpty()
        )
    }

    // -------------------------------------------------------------------------
    // handleStart
    // -------------------------------------------------------------------------

    @Test
    fun `handleStart emits HEALTHY on success`() = runBlocking {
        every { containerManager.getContainerDataPath(any()) } returns null
        every { containerManager.startContainer(any()) } just Runs
        val outbound = channel()

        handler.handleStart(startContainerCommand {
            serverId = "srv-start"
            containerName = "craftpanel-start"
        }, outbound)

        val msg = outbound.messages()
            .single()
        assertEquals(ServerStatusUpdate.ServerStatus.HEALTHY, msg.serverStatus.status)
        assertEquals("srv-start", msg.serverStatus.serverId)
    }

    @Test
    fun `handleStart emits UNHEALTHY on failure`() = runBlocking {
        every { containerManager.getContainerDataPath(any()) } returns null
        every { containerManager.startContainer(any()) } throws RuntimeException("start failed")
        val outbound = channel()

        handler.handleStart(startContainerCommand {
            serverId = "srv-start-fail"
            containerName = "craftpanel-start-fail"
        }, outbound)

        assertEquals(
            ServerStatusUpdate.ServerStatus.UNHEALTHY,
            outbound.messages()
                .single().serverStatus.status
        )
    }

    // -------------------------------------------------------------------------
    // handleStop
    // -------------------------------------------------------------------------

    @Test
    fun `handleStop emits STOPPED on success`() = runBlocking {
        every { containerManager.stopContainer(any(), any(), any()) } just Runs
        val outbound = channel()

        handler.handleStop(stopContainerCommand {
            serverId = "srv-stop"
            containerName = "craftpanel-stop"
            timeoutSeconds = 10
        }, outbound)

        val msg = outbound.messages()
            .single()
        assertEquals(ServerStatusUpdate.ServerStatus.STOPPED, msg.serverStatus.status)
        assertEquals("srv-stop", msg.serverStatus.serverId)
    }

    @Test
    fun `handleStop emits UNHEALTHY on failure`() = runBlocking {
        every { containerManager.stopContainer(any(), any(), any()) } throws RuntimeException("stop failed")
        val outbound = channel()

        handler.handleStop(stopContainerCommand {
            serverId = "srv-stop-fail"
            containerName = "craftpanel-stop-fail"
        }, outbound)

        assertEquals(
            ServerStatusUpdate.ServerStatus.UNHEALTHY,
            outbound.messages()
                .single().serverStatus.status
        )
    }

    // -------------------------------------------------------------------------
    // handleRestart
    // -------------------------------------------------------------------------

    @Test
    fun `handleRestart emits HEALTHY after stop then start`() = runBlocking {
        every { containerManager.stopContainer(any(), any(), any()) } just Runs
        every { containerManager.startContainer(any()) } just Runs
        val outbound = channel()

        handler.handleRestart(restartContainerCommand {
            serverId = "srv-restart"
            containerName = "craftpanel-restart"
            timeoutSeconds = 10
        }, outbound)

        assertEquals(
            ServerStatusUpdate.ServerStatus.HEALTHY,
            outbound.messages()
                .single().serverStatus.status
        )
        verify { containerManager.stopContainer("craftpanel-restart", 10, "") }
        verify { containerManager.startContainer("craftpanel-restart") }
    }

    @Test
    fun `handleRestart emits UNHEALTHY when stop fails`() = runBlocking {
        every { containerManager.stopContainer(any(), any(), any()) } throws RuntimeException("stop failed")
        val outbound = channel()

        handler.handleRestart(restartContainerCommand {
            serverId = "srv-restart-fail"
            containerName = "craftpanel-restart-fail"
        }, outbound)

        assertEquals(
            ServerStatusUpdate.ServerStatus.UNHEALTHY,
            outbound.messages()
                .single().serverStatus.status
        )
    }

    // -------------------------------------------------------------------------
    // handleRemove
    // -------------------------------------------------------------------------

    @Test
    fun `handleRemove calls containerManager removeContainer`() = runBlocking {
        every { containerManager.removeContainer(any(), any()) } just Runs

        handler.handleRemove(removeContainerCommand {
            containerName = "craftpanel-remove"
            force = true
        })

        verify { containerManager.removeContainer("craftpanel-remove", true) }
    }

    @Test
    fun `handleRemove does not throw on docker error`() = runBlocking {
        every { containerManager.removeContainer(any(), any()) } throws RuntimeException("rm failed")

        handler.handleRemove(removeContainerCommand {
            containerName = "craftpanel-rm-fail"
            force = false
        })
    }

    // -------------------------------------------------------------------------
    // handleShutdown
    // -------------------------------------------------------------------------

    @Test
    fun `handleShutdown emits shutdownAcknowledge with container counts`() = runBlocking {
        every { containerManager.shutdownAll(any()) } returns Pair(3, 1)
        val outbound = channel()

        handler.handleShutdown(shutdownCommand { timeoutSeconds = 30 }, outbound)

        val msg = outbound.messages()
            .single()
        assertTrue(msg.hasShutdownAcknowledge())
        assertEquals(3, msg.shutdownAcknowledge.gracefulCount)
        assertEquals(1, msg.shutdownAcknowledge.forcedCount)
    }

    // -------------------------------------------------------------------------
    // handleDeleteBackup
    // -------------------------------------------------------------------------

    @Test
    fun `handleDeleteBackup deletes the file`() = runBlocking {
        val file = File(tempDir, "backup.tar.gz").also { it.writeText("dummy") }

        handler.handleDeleteBackup(deleteBackupCommand { filePath = file.absolutePath })

        assertFalse(file.exists())
    }

    @Test
    fun `handleDeleteBackup does not throw when file does not exist`() = runBlocking {
        handler.handleDeleteBackup(deleteBackupCommand { filePath = "/nonexistent/backup.tar.gz" })
    }

    // -------------------------------------------------------------------------
    // handleTriggerBackup
    // -------------------------------------------------------------------------

    @Test
    fun `handleTriggerBackup emits failure when server data dir does not exist`() = runBlocking {
        val outbound = channel()

        handler.handleTriggerBackup(triggerBackupCommand {
            backupId = "bk-1"
            serverId = "srv-bk"
            containerName = "craftpanel-mc"
        }, outbound)

        val messages = outbound.messages()
        val complete = messages.last { it.hasBackupComplete() }
        assertEquals("bk-1", complete.backupComplete.backupId)
        assertFalse(complete.backupComplete.success)
        assertTrue(complete.backupComplete.errorMessage.isNotEmpty())
    }

    @Test
    fun `handleTriggerBackup emits progress and success for valid source directory`() = runBlocking {
        val bkHandler = handlerWithDataPath(tempDir.absolutePath)
        val serverId = "srv-bk-2"
        File(tempDir, "servers/$serverId").also { it.mkdirs() }
            .let { File(it, "world").writeText("level data") }
        val outbound = channel()

        bkHandler.handleTriggerBackup(triggerBackupCommand {
            backupId = "bk-2"
            this.serverId = serverId
            containerName = "craftpanel-mc"
        }, outbound)

        val messages = outbound.messages()
        assertTrue(messages.any { it.hasBackupProgress() }, "Expected progress updates")
        assertTrue(messages.any { it.hasBackupComplete() }, "Expected completion message")
        assertTrue(messages.last { it.hasBackupComplete() }.backupComplete.success)
        assertTrue(File(tempDir, "backups/bk-2.tar.gz").exists(), "Expected tar file to be created")
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private fun channel() = Channel<AgentMessage>(Channel.UNLIMITED)

    private fun handlerWithDataPath(path: String) = ControlStreamHandler(
        identity,
        config.copy(dataBasePath = path, hostDataBasePath = path),
        containerManager,
        metricsCollector,
        docker,
    )

    private fun Channel<AgentMessage>.messages(): List<AgentMessage> = buildList {
        while (true) {
            val r = tryReceive()
            if (r.isSuccess) add(r.getOrThrow()) else break
        }
    }
}
