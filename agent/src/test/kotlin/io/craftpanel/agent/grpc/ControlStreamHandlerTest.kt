package io.craftpanel.agent.grpc

import com.craftpanel.agent.v1.AgentMessage
import com.craftpanel.agent.v1.ContainerState
import com.craftpanel.agent.v1.ServerStatusUpdate
import com.craftpanel.agent.v1.containerState
import com.craftpanel.agent.v1.createContainerCommand
import com.craftpanel.agent.v1.deleteBackupCommand
import com.craftpanel.agent.v1.removeContainerCommand
import com.craftpanel.agent.v1.restartContainerCommand
import com.craftpanel.agent.v1.shutdownCommand
import com.craftpanel.agent.v1.startContainerCommand
import com.craftpanel.agent.v1.stopContainerCommand
import com.craftpanel.agent.v1.triggerBackupCommand
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.MetricsCollector
import io.mockk.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlStreamHandlerTest {

    private val containerManager: ContainerManager = mockk()
    private val metricsCollector: MetricsCollector = mockk(relaxed = true)
    private val identity = NodeIdentity(nodeId = "node-1", nodeKey = "test-key")
    private val handler = ControlStreamHandler(identity, containerManager, metricsCollector)

    private lateinit var tempDir: File

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("handler-test").toFile()
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
        every { containerManager.createContainer(any()) } returns "new-container-id"
        val outbound = flow()

        handler.handleCreate("corr-1", createContainerCommand {
            serverId = "srv-create"
            containerName = "craftpanel-create"
            image = "itzg/minecraft-server:latest"
        }, outbound)

        val msg = outbound.replayCache.single()
        assertTrue(msg.hasServerStatus())
        assertEquals("srv-create", msg.serverStatus.serverId)
        assertEquals(ServerStatusUpdate.ServerStatus.STOPPED, msg.serverStatus.status)
        assertEquals("new-container-id", msg.serverStatus.containerId)
    }

    @Test
    fun `handleCreate emits nothing on failure`() = runBlocking {
        every { containerManager.createContainer(any()) } throws RuntimeException("docker error")
        val outbound = flow()

        handler.handleCreate("corr-1", createContainerCommand {
            serverId = "srv-fail"
            containerName = "craftpanel-fail"
            image = "itzg/minecraft-server:latest"
        }, outbound)

        assertTrue(outbound.replayCache.isEmpty())
    }

    // -------------------------------------------------------------------------
    // handleStart
    // -------------------------------------------------------------------------

    @Test
    fun `handleStart emits HEALTHY on success`() = runBlocking {
        every { containerManager.startContainer(any()) } just Runs
        val outbound = flow()

        handler.handleStart("corr-1", startContainerCommand {
            serverId = "srv-start"
            containerName = "craftpanel-start"
        }, outbound)

        val msg = outbound.replayCache.single()
        assertEquals(ServerStatusUpdate.ServerStatus.HEALTHY, msg.serverStatus.status)
        assertEquals("srv-start", msg.serverStatus.serverId)
    }

    @Test
    fun `handleStart emits UNHEALTHY on failure`() = runBlocking {
        every { containerManager.startContainer(any()) } throws RuntimeException("start failed")
        val outbound = flow()

        handler.handleStart("corr-1", startContainerCommand {
            serverId = "srv-start-fail"
            containerName = "craftpanel-start-fail"
        }, outbound)

        assertEquals(ServerStatusUpdate.ServerStatus.UNHEALTHY, outbound.replayCache.single().serverStatus.status)
    }

    // -------------------------------------------------------------------------
    // handleStop
    // -------------------------------------------------------------------------

    @Test
    fun `handleStop emits STOPPED on success`() = runBlocking {
        every { containerManager.stopContainer(any(), any(), any()) } just Runs
        val outbound = flow()

        handler.handleStop("corr-1", stopContainerCommand {
            serverId = "srv-stop"
            containerName = "craftpanel-stop"
            timeoutSeconds = 10
        }, outbound)

        val msg = outbound.replayCache.single()
        assertEquals(ServerStatusUpdate.ServerStatus.STOPPED, msg.serverStatus.status)
        assertEquals("srv-stop", msg.serverStatus.serverId)
    }

    @Test
    fun `handleStop emits UNHEALTHY on failure`() = runBlocking {
        every { containerManager.stopContainer(any(), any(), any()) } throws RuntimeException("stop failed")
        val outbound = flow()

        handler.handleStop("corr-1", stopContainerCommand {
            serverId = "srv-stop-fail"
            containerName = "craftpanel-stop-fail"
        }, outbound)

        assertEquals(ServerStatusUpdate.ServerStatus.UNHEALTHY, outbound.replayCache.single().serverStatus.status)
    }

    // -------------------------------------------------------------------------
    // handleRestart
    // -------------------------------------------------------------------------

    @Test
    fun `handleRestart emits HEALTHY after stop then start`() = runBlocking {
        every { containerManager.stopContainer(any(), any(), any()) } just Runs
        every { containerManager.startContainer(any()) } just Runs
        val outbound = flow()

        handler.handleRestart("corr-1", restartContainerCommand {
            serverId = "srv-restart"
            containerName = "craftpanel-restart"
            timeoutSeconds = 10
        }, outbound)

        assertEquals(ServerStatusUpdate.ServerStatus.HEALTHY, outbound.replayCache.single().serverStatus.status)
        verify { containerManager.stopContainer("craftpanel-restart", 10, "") }
        verify { containerManager.startContainer("craftpanel-restart") }
    }

    @Test
    fun `handleRestart emits UNHEALTHY when stop fails`() = runBlocking {
        every { containerManager.stopContainer(any(), any(), any()) } throws RuntimeException("stop failed")
        val outbound = flow()

        handler.handleRestart("corr-1", restartContainerCommand {
            serverId = "srv-restart-fail"
            containerName = "craftpanel-restart-fail"
        }, outbound)

        assertEquals(ServerStatusUpdate.ServerStatus.UNHEALTHY, outbound.replayCache.single().serverStatus.status)
    }

    // -------------------------------------------------------------------------
    // handleRemove
    // -------------------------------------------------------------------------

    @Test
    fun `handleRemove calls containerManager removeContainer`() = runBlocking {
        every { containerManager.removeContainer(any(), any()) } just Runs

        handler.handleRemove("corr-1", removeContainerCommand {
            containerName = "craftpanel-remove"
            force = true
        })

        verify { containerManager.removeContainer("craftpanel-remove", true) }
    }

    @Test
    fun `handleRemove does not throw on docker error`() = runBlocking {
        every { containerManager.removeContainer(any(), any()) } throws RuntimeException("rm failed")

        handler.handleRemove("corr-1", removeContainerCommand {
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
        val outbound = flow()

        handler.handleShutdown(shutdownCommand { timeoutSeconds = 30 }, outbound)

        val msg = outbound.replayCache.single()
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
    fun `handleTriggerBackup emits failure when container data path not found`() = runBlocking {
        every { containerManager.getContainerDataPath(any()) } returns null
        val outbound = flow()

        handler.handleTriggerBackup(triggerBackupCommand {
            backupId = "bk-1"
            serverId = "srv-bk"
            containerName = "craftpanel-mc"
            destinationPath = "/backups/bk-1.tar.gz"
        }, outbound)

        val msg = outbound.replayCache.single()
        assertTrue(msg.hasBackupComplete())
        assertEquals("bk-1", msg.backupComplete.backupId)
        assertFalse(msg.backupComplete.success)
        assertTrue(msg.backupComplete.errorMessage.isNotEmpty())
    }

    @Test
    fun `handleTriggerBackup emits progress and success for valid source directory`() = runBlocking {
        val sourceDir = File(tempDir, "data").also { it.mkdirs() }
        File(sourceDir, "world").writeText("level data")
        val destPath = File(tempDir, "backup.tar.gz").absolutePath

        every { containerManager.getContainerDataPath(any()) } returns sourceDir.absolutePath
        val outbound = flow()

        handler.handleTriggerBackup(triggerBackupCommand {
            backupId = "bk-2"
            serverId = "srv-bk-2"
            containerName = "craftpanel-mc"
            destinationPath = destPath
        }, outbound)

        val messages = outbound.replayCache
        assertTrue(messages.any { it.hasBackupProgress() }, "Expected progress updates")
        assertTrue(messages.any { it.hasBackupComplete() }, "Expected completion message")
        assertTrue(messages.last { it.hasBackupComplete() }.backupComplete.success)
        assertTrue(File(destPath).exists(), "Expected tar file to be created")
    }

    // -------------------------------------------------------------------------
    // helper
    // -------------------------------------------------------------------------

    private fun flow() = MutableSharedFlow<AgentMessage>(replay = 32, extraBufferCapacity = 32)
}
