package io.craftpanel.master.service

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.proto.MasterMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class ContainerLifecycleTest {

    private val sent = mutableListOf<Pair<String, MasterMessage>>()
    private val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
    private lateinit var nodeId: Uuid
    private lateinit var serverId: Uuid

    @BeforeTest
    fun setup() {
        TestDatabase.initIfNeeded()
        TestDatabase.reset()
        sent.clear()
        val resolvedNodeId = transaction {
            Nodes.insert {
                it[Nodes.hostname] = "test-node"
                it[Nodes.displayName] = "test-node"
                it[Nodes.publicIp] = "1.2.3.4"
                it[Nodes.privateIp] = "10.0.0.1"
                it[Nodes.tokenHash] = "a".repeat(64)
                it[Nodes.status] = "ACTIVE"
                it[Nodes.totalRamMb] = 8192
                it[Nodes.totalCpuShares] = 0
                it[Nodes.portRangeStart] = 25565
                it[Nodes.portRangeEnd] = 25600
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }
        nodeId = resolvedNodeId
        serverId = transaction {
            Servers.insert {
                it[Servers.nodeId] = resolvedNodeId
                it[Servers.name] = "test-server"
                it[Servers.displayName] = "test-server"
                it[Servers.serverType] = "VANILLA"
                it[Servers.mcVersion] = "1.21.4"
                it[Servers.itzgImageTag] = "latest"
                it[Servers.hostPort] = 25565
                it[Servers.memoryMb] = 1024
                it[Servers.cpuShares] = 0
                it[Servers.status] = "STOPPED"
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }
    }

    private fun serverRow(): ResultRow = transaction {
        Servers.selectAll()
            .where { Servers.id eq serverId }
            .first()
    }

    private fun lifecycle(
        startTimeout: kotlin.time.Duration = 2.seconds,
        stopTimeout: kotlin.time.Duration = 2.seconds,
        removeTimeout: kotlin.time.Duration = 2.seconds,
    ) = ContainerLifecycle(
        sendToNode = { nId, msg -> sent.add(nId to msg); true },
        agentEvents = events,
        modService = ModService(),
        startTimeout = startTimeout,
        stopTimeout = stopTimeout,
        removeTimeout = removeTimeout,
    )

    @Test
    fun `start - needsRecreate false - sends single StartContainerCommand`() = runTest {
        val server = serverRow()
        val lc = lifecycle()
        launch {
            delay(50.milliseconds)
            events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.HEALTHY))
        }
        lc.start(server, needsRecreate = false)
        assertEquals(1, sent.size)
        assert(sent[0].second.hasStartContainer())
        val cmd = sent[0].second.startContainer
        assert(!cmd.needsRecreate)
        assertEquals("craftpanel-$serverId", cmd.containerName)
        assertEquals("itzg/minecraft-server:latest", cmd.image)
        assertEquals("TRUE", cmd.envVarsMap["EULA"])
    }

    @Test
    fun `start - needsRecreate true - sends StartContainerCommand with needsRecreate=true`() = runTest {
        val server = serverRow()
        val lc = lifecycle()
        launch {
            delay(50.milliseconds)
            events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.HEALTHY))
        }
        lc.start(server, needsRecreate = true)
        assertEquals(1, sent.size)
        assert(sent[0].second.hasStartContainer())
        val cmd = sent[0].second.startContainer
        assert(cmd.needsRecreate)
    }

    @Test
    fun `start - UNHEALTHY response - throws ContainerLifecycleException`() = runTest {
        val server = serverRow()
        val lc = lifecycle()
        launch {
            delay(50.milliseconds)
            events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.UNHEALTHY))
        }
        assertFailsWith<ContainerLifecycleException> {
            lc.start(server, needsRecreate = false)
        }
    }

    @Test
    fun `start - timeout - throws ContainerLifecycleException`() = runTest {
        val server = serverRow()
        val lc = lifecycle(startTimeout = 100.milliseconds)
        assertFailsWith<ContainerLifecycleException> {
            lc.start(server, needsRecreate = false)
        }
    }

    @Test
    fun `stop - agent not connected - throws BadGatewayException`() = runTest {
        val server = serverRow()
        val lc = ContainerLifecycle(
            sendToNode = { _, _ -> false },
            agentEvents = events,
            modService = ModService(),
        )
        assertFailsWith<BadGatewayException> {
            lc.stop(server, nodeId.toString())
        }
    }
}
