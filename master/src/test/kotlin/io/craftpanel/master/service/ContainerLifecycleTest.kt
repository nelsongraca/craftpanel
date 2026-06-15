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
import org.jetbrains.exposed.v1.jdbc.update
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
                it[Servers.containerId] = null
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }
    }

    private fun serverRow(): ResultRow = transaction {
        Servers.selectAll().where { Servers.id eq serverId }.first()
    }

    private fun lifecycle(
        createTimeout: kotlin.time.Duration = 2.seconds,
        startTimeout: kotlin.time.Duration = 2.seconds,
        stopTimeout: kotlin.time.Duration = 2.seconds,
        removeTimeout: kotlin.time.Duration = 2.seconds,
    ) = ContainerLifecycle(
        sendToNode = { nId, msg -> sent.add(nId to msg); true },
        agentEvents = events,
        modService = ModService(),
        createTimeout = createTimeout,
        startTimeout = startTimeout,
        stopTimeout = stopTimeout,
        removeTimeout = removeTimeout,
    )

    @Test
    fun `start - no existing container - sends create then start`() = runTest {
        val server = serverRow()
        val lc = lifecycle()
        launch {
            delay(50.milliseconds)
            events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.STOPPED, "cid-1"))
            delay(50.milliseconds)
            events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.HEALTHY, "cid-1"))
        }
        lc.start(server, pull = false)
        assertEquals(2, sent.size)
        assert(sent[0].second.hasCreateContainer())
        assert(sent[1].second.hasStartContainer())
    }

    @Test
    fun `start - existing container and pull true - sends remove create start`() = runTest {
        transaction {
            Servers.update({ Servers.id eq serverId }) {
                it[Servers.containerId] = "old-cid"
            }
        }
        val server = serverRow()
        val lc = lifecycle()
        launch {
            delay(50.milliseconds)
            events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.STOPPED, ""))
            delay(50.milliseconds)
            events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.STOPPED, "new-cid"))
            delay(50.milliseconds)
            events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.HEALTHY, "new-cid"))
        }
        lc.start(server, pull = true)
        assertEquals(3, sent.size)
        assert(sent[0].second.hasRemoveContainer())
        assert(sent[1].second.hasCreateContainer())
        assert(sent[2].second.hasStartContainer())
    }

    @Test
    fun `start - UNHEALTHY on create - throws ContainerLifecycleException`() = runTest {
        val server = serverRow()
        val lc = lifecycle()
        launch {
            delay(50.milliseconds)
            events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.UNHEALTHY, ""))
        }
        assertFailsWith<ContainerLifecycleException> {
            lc.start(server, pull = false)
        }
    }

    @Test
    fun `start - timeout on create - throws ContainerLifecycleException`() = runTest {
        val server = serverRow()
        val lc = lifecycle(createTimeout = 100.milliseconds)
        assertFailsWith<ContainerLifecycleException> {
            lc.start(server, pull = false)
        }
    }

    @Test
    fun `recreate - sends stop remove create start in order`() = runTest {
        transaction {
            Servers.update({ Servers.id eq serverId }) {
                it[Servers.containerId] = "existing-cid"
            }
        }
        val server = serverRow()
        val lc = lifecycle()
        launch {
            delay(50.milliseconds)
            events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.STOPPED, ""))
            delay(50.milliseconds)
            events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.STOPPED, ""))
            delay(50.milliseconds)
            events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.STOPPED, "new-cid"))
            delay(50.milliseconds)
            events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.HEALTHY, "new-cid"))
        }
        lc.recreate(server, hostnameOverride = null)
        assertEquals(4, sent.size)
        assert(sent[0].second.hasStopContainer())
        assert(sent[1].second.hasRemoveContainer())
        assert(sent[2].second.hasCreateContainer())
        assert(sent[3].second.hasStartContainer())
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
