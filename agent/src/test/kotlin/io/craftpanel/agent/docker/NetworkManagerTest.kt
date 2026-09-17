package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.*
import com.github.dockerjava.api.model.Network
import com.github.dockerjava.api.command.InspectContainerResponse
import io.kotest.core.spec.style.FunSpec
import io.mockk.*

class NetworkManagerTest : FunSpec({

    fun networkCmd(docker: DockerClient, networkName: String, network: Network?): ListNetworksCmd {
        val cmd = mockk<ListNetworksCmd>()
        every { docker.listNetworksCmd() } returns cmd
        every { cmd.withNameFilter(networkName) } returns cmd
        every { cmd.exec() } returns listOfNotNull(network)
        return cmd
    }

    test("creates a managed bridge network when missing") {
        val docker = mockk<DockerClient>()
        networkCmd(docker, "server-net", null)
        val create = mockk<CreateNetworkCmd>()
        every { docker.createNetworkCmd() } returns create
        every { create.withName("server-net") } returns create
        every { create.withDriver("bridge") } returns create
        every { create.withLabels(mapOf("craftpanel.managed" to "true")) } returns create
        every { create.exec() } returns mockk(relaxed = true)

        NetworkManager(docker, "router").ensureNetwork("server-net")

        verify { create.exec() }
    }

    test("reuses an existing network without creating it") {
        val docker = mockk<DockerClient>()
        val network = mockk<Network>()
        every { network.id } returns "network-id"
        every { network.name } returns "server-net"
        networkCmd(docker, "server-net", network)

        NetworkManager(docker, "router").ensureNetwork("server-net")

        verify(exactly = 0) { docker.createNetworkCmd() }
    }

    test("attaches the router when it is not already connected") {
        val docker = mockk<DockerClient>()
        val network = mockk<Network>()
        every { network.id } returns "network-id"
        every { network.name } returns "server-net"
        every { network.containers } returns emptyMap()
        networkCmd(docker, "server-net", network)
        val inspect = mockk<InspectContainerCmd>()
        every { docker.inspectContainerCmd("router") } returns inspect
        every { inspect.exec() } returns mockk<InspectContainerResponse> {
            every { id } returns "router-id"
        }
        val connect = mockk<ConnectToNetworkCmd>()
        every { docker.connectToNetworkCmd() } returns connect
        every { connect.withNetworkId("server-net") } returns connect
        every { connect.withContainerId("router-id") } returns connect

        NetworkManager(docker, "router").attachToNetwork("server-net")

        verify { connect.exec() }
    }

    test("detaches the router and deletes an empty network") {
        val docker = mockk<DockerClient>()
        val network = mockk<Network>()
        every { network.id } returns "network-id"
        every { network.name } returns "server-net"
        every { network.containers } returns mapOf("server-id" to mockk())
        networkCmd(docker, "server-net", network)
        val inspect = mockk<InspectContainerCmd>()
        every { docker.inspectContainerCmd("router") } returns inspect
        every { inspect.exec() } returns mockk<InspectContainerResponse> {
            every { id } returns "router-id"
        }
        val disconnect = mockk<DisconnectFromNetworkCmd>()
        every { docker.disconnectFromNetworkCmd() } returns disconnect
        every { disconnect.withNetworkId("server-net") } returns disconnect
        every { disconnect.withContainerId("router-id") } returns disconnect
        val remove = mockk<RemoveNetworkCmd>()
        every { docker.removeNetworkCmd("network-id") } returns remove

        NetworkManager(docker, "router").maybeDetachAndDelete("server-net", "server-id")

        verify { disconnect.exec() }
        verify { remove.exec() }
    }
})
