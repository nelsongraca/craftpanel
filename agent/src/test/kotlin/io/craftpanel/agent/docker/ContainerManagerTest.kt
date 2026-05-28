package io.craftpanel.agent.docker

import com.craftpanel.agent.v1.ContainerState
import com.craftpanel.agent.v1.createContainerCommand
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.CreateContainerCmd
import com.github.dockerjava.api.command.CreateContainerResponse
import com.github.dockerjava.api.command.KillContainerCmd
import com.github.dockerjava.api.command.ListContainersCmd
import com.github.dockerjava.api.command.RemoveContainerCmd
import com.github.dockerjava.api.command.StartContainerCmd
import com.github.dockerjava.api.command.StopContainerCmd
import com.github.dockerjava.api.model.Container
import com.github.dockerjava.api.model.ExposedPort
import io.mockk.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ContainerManagerTest {

    private val docker: DockerClient = mockk()
    private val manager = ContainerManager(docker)

    // -------------------------------------------------------------------------
    // listContainers
    // -------------------------------------------------------------------------

    @Test
    fun `listContainers maps running state to RUNNING`() {
        stubListAll(listOf(fakeContainer(name = "/craftpanel-test", state = "running", status = "Up 2 hours")))

        val result = manager.listContainers()

        assertEquals(1, result.size)
        assertEquals(ContainerState.RunState.RUNNING, result[0].runState)
    }

    @Test
    fun `listContainers maps exited with code 0 to STOPPED`() {
        stubListAll(listOf(fakeContainer(name = "/craftpanel-test", state = "exited", status = "Exited (0) 5 minutes ago")))

        assertEquals(ContainerState.RunState.STOPPED, manager.listContainers()[0].runState)
    }

    @Test
    fun `listContainers maps non-zero exited to EXITED`() {
        stubListAll(listOf(fakeContainer(name = "/craftpanel-test", state = "exited", status = "Exited (137) 1 hour ago")))

        assertEquals(ContainerState.RunState.EXITED, manager.listContainers()[0].runState)
    }

    @Test
    fun `listContainers filters out containers without craftpanel- prefix`() {
        stubListAll(listOf(
            fakeContainer(name = "/craftpanel-web", state = "running", status = "Up"),
            fakeContainer(name = "/nginx", state = "running", status = "Up"),
        ))

        val result = manager.listContainers()

        assertEquals(1, result.size)
        assertEquals("craftpanel-web", result[0].containerName)
    }

    @Test
    fun `listContainers sets serverId from label`() {
        stubListAll(listOf(fakeContainer(name = "/craftpanel-abc", state = "running", status = "Up", serverId = "server-uuid-123")))

        assertEquals("server-uuid-123", manager.listContainers()[0].serverId)
    }

    @Test
    fun `listContainers strips leading slash from container name`() {
        stubListAll(listOf(fakeContainer(name = "/craftpanel-srv", state = "running", status = "Up")))

        assertEquals("craftpanel-srv", manager.listContainers()[0].containerName)
    }

    // -------------------------------------------------------------------------
    // listRunningContainerIds
    // -------------------------------------------------------------------------

    @Test
    fun `listRunningContainerIds returns only containers with craftpanel server id label`() {
        stubListRunning(listOf(
            fakeContainer(name = "/craftpanel-mc", state = "running", status = "Up", serverId = "srv-1"),
            fakeContainer(name = "/craftpanel-other", state = "running", status = "Up", serverId = null),
        ))

        val result = manager.listRunningContainerIds()

        assertEquals(1, result.size)
        assertEquals("srv-1", result[0].first)
    }

    @Test
    fun `listRunningContainerIds returns empty list when no containers`() {
        stubListRunning(emptyList())
        assertEquals(0, manager.listRunningContainerIds().size)
    }

    // -------------------------------------------------------------------------
    // createContainer
    // -------------------------------------------------------------------------

    @Test
    fun `createContainer returns container id from docker response`() {
        stubCreate("new-container-id")

        val id = manager.createContainer(createContainerCommand {
            containerName = "craftpanel-test"
            serverId = "srv-1"
            image = "itzg/minecraft-server:latest"
            hostPort = 25565
        })

        assertEquals("new-container-id", id)
    }

    @Test
    fun `createContainer sets server id label`() {
        val createCmd = stubCreate("c1")

        manager.createContainer(createContainerCommand {
            containerName = "craftpanel-srv"
            serverId = "srv-label-test"
            image = "itzg/minecraft-server:latest"
        })

        verify { createCmd.withLabels(mapOf("craftpanel.server.id" to "srv-label-test")) }
    }

    // -------------------------------------------------------------------------
    // startContainer
    // -------------------------------------------------------------------------

    @Test
    fun `startContainer invokes docker start`() {
        val startCmd = mockk<StartContainerCmd>(relaxed = true)
        every { docker.startContainerCmd("craftpanel-mc") } returns startCmd

        manager.startContainer("craftpanel-mc")

        verify { startCmd.exec() }
    }

    // -------------------------------------------------------------------------
    // stopContainer
    // -------------------------------------------------------------------------

    @Test
    fun `stopContainer calls docker stop with specified timeout`() {
        val stopCmd = mockk<StopContainerCmd>(relaxed = true)
        every { docker.stopContainerCmd("craftpanel-mc") } returns stopCmd
        every { stopCmd.withTimeout(any()) } returns stopCmd

        manager.stopContainer("craftpanel-mc", timeoutSeconds = 15, stopCommand = "")

        verify { stopCmd.withTimeout(15) }
        verify { stopCmd.exec() }
    }

    @Test
    fun `stopContainer uses default timeout of 30 when timeout is zero`() {
        val stopCmd = mockk<StopContainerCmd>(relaxed = true)
        every { docker.stopContainerCmd("craftpanel-mc") } returns stopCmd
        every { stopCmd.withTimeout(any()) } returns stopCmd

        manager.stopContainer("craftpanel-mc", timeoutSeconds = 0, stopCommand = "")

        verify { stopCmd.withTimeout(30) }
    }

    // -------------------------------------------------------------------------
    // removeContainer
    // -------------------------------------------------------------------------

    @Test
    fun `removeContainer calls docker remove with force flag`() {
        val removeCmd = mockk<RemoveContainerCmd>(relaxed = true)
        every { docker.removeContainerCmd("craftpanel-mc") } returns removeCmd
        every { removeCmd.withForce(true) } returns removeCmd

        manager.removeContainer("craftpanel-mc", force = true)

        verify { removeCmd.withForce(true) }
        verify { removeCmd.exec() }
    }

    // -------------------------------------------------------------------------
    // shutdownAll
    // -------------------------------------------------------------------------

    @Test
    fun `shutdownAll counts graceful stops`() {
        stubListRunning(listOf(
            fakeContainer(name = "/craftpanel-a", state = "running", status = "Up", id = "c1"),
            fakeContainer(name = "/craftpanel-b", state = "running", status = "Up", id = "c2"),
        ))
        stubStop("c1")
        stubStop("c2")

        val (graceful, forced) = manager.shutdownAll(10)

        assertEquals(2, graceful)
        assertEquals(0, forced)
    }

    @Test
    fun `shutdownAll counts forced kills when graceful stop fails`() {
        stubListRunning(listOf(fakeContainer(name = "/craftpanel-a", state = "running", status = "Up", id = "c1")))

        val stopCmd = mockk<StopContainerCmd>(relaxed = true)
        every { docker.stopContainerCmd("c1") } returns stopCmd
        every { stopCmd.withTimeout(any()) } returns stopCmd
        every { stopCmd.exec() } throws RuntimeException("stop failed")

        val killCmd = mockk<KillContainerCmd>(relaxed = true)
        every { docker.killContainerCmd("c1") } returns killCmd

        val (graceful, forced) = manager.shutdownAll(10)

        assertEquals(0, graceful)
        assertEquals(1, forced)
    }

    @Test
    fun `shutdownAll returns zeros when no craftpanel containers running`() {
        stubListRunning(emptyList())

        val (graceful, forced) = manager.shutdownAll(10)

        assertEquals(0, graceful)
        assertEquals(0, forced)
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private val objectMapper = ObjectMapper()

    private fun fakeContainer(
        name: String,
        state: String,
        status: String,
        id: String = "container-id",
        serverId: String? = "server-id",
    ): Container {
        val labelsJson = if (serverId != null)
            """{"craftpanel.server.id":"$serverId"}"""
        else "{}"
        val json = """{"Id":"$id","Names":["$name"],"State":"$state","Status":"$status","Labels":$labelsJson}"""
        return objectMapper.readValue(json, Container::class.java)
    }

    private fun stubListAll(containers: List<Container>) {
        val cmd = mockk<ListContainersCmd>()
        every { docker.listContainersCmd() } returns cmd
        every { cmd.withShowAll(true) } returns cmd
        every { cmd.exec() } returns containers
    }

    private fun stubListRunning(containers: List<Container>) {
        val cmd = mockk<ListContainersCmd>()
        every { docker.listContainersCmd() } returns cmd
        every { cmd.withShowAll(false) } returns cmd
        every { cmd.exec() } returns containers
    }

    private fun stubCreate(returnedId: String): CreateContainerCmd {
        val createCmd = mockk<CreateContainerCmd>(relaxed = true)
        every { docker.createContainerCmd(any()) } returns createCmd
        every { createCmd.withName(any()) } returns createCmd
        every { createCmd.withEnv(any<List<String>>()) } returns createCmd
        every { createCmd.withExposedPorts(any<ExposedPort>()) } returns createCmd
        every { createCmd.withHostConfig(any()) } returns createCmd
        every { createCmd.withLabels(any()) } returns createCmd
        every { createCmd.withStdinOpen(any()) } returns createCmd
        val response = mockk<CreateContainerResponse>()
        every { response.id } returns returnedId
        every { createCmd.exec() } returns response
        return createCmd
    }

    private fun stubStop(containerId: String) {
        val stopCmd = mockk<StopContainerCmd>(relaxed = true)
        every { docker.stopContainerCmd(containerId) } returns stopCmd
        every { stopCmd.withTimeout(any()) } returns stopCmd
    }
}
