package io.craftpanel.master.grpc

import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.TestDatabase
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.NodeHealth
import io.craftpanel.master.grpc.handlers.*
import io.craftpanel.master.service.NodeStateReconciler
import io.craftpanel.proto.AgentMessage
import io.craftpanel.proto.ConsoleOutput
import io.craftpanel.proto.agentMessage
import io.craftpanel.proto.nodeStateSnapshot
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import io.craftpanel.master.service.repo.NodeRepositoryImpl
import io.craftpanel.master.service.repo.ServerRepositoryImpl
import kotlin.uuid.Uuid
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

class ControlServiceImplTest : FunSpec({
    val reconciler = NodeStateReconciler(ServerRepositoryImpl(), NodeRepositoryImpl())
    val agentEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 1024)
    val dataOpContext = DataOpContext(ConcurrentHashMap(), ConcurrentHashMap())
    val nodeStateHandler = NodeStateHandler(agentEvents, reconciler)
    val nodeMetricsHandler = NodeMetricsHandler(agentEvents, reconciler)
    val containerMetricsHandler = ContainerMetricsHandler(agentEvents)
    val serverStatusHandler = ServerStatusHandler(agentEvents)
    val playerUpdateHandler = PlayerUpdateHandler(agentEvents)
    val backupHandler = BackupHandler(agentEvents)
    val migrationHandler = MigrationHandler(agentEvents)
    val dataOpResponseHandler = DataOpResponseHandler(dataOpContext)
    val service = ControlServiceImpl(
        nodeConfig = NodeConfig(bootstrapToken = "test-token", agentDataPort = 50052),
        nodeStateReconciler = reconciler,
        agentEventsFlow = agentEvents,
        dataOpContext = dataOpContext,
        nodeStateHandler = nodeStateHandler,
        nodeMetricsHandler = nodeMetricsHandler,
        containerMetricsHandler = containerMetricsHandler,
        serverStatusHandler = serverStatusHandler,
        playerUpdateHandler = playerUpdateHandler,
        backupHandler = backupHandler,
        migrationHandler = migrationHandler,
        dataOpResponseHandler = dataOpResponseHandler,
    )

    beforeTest {
        TestDatabase.initIfNeeded()
        TestDatabase.reset()
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    fun createNode(status: String = "ACTIVE", health: String = "HEALTHY"): Uuid = transaction {
        Nodes.insert {
            it[Nodes.hostname] = "test-node-${Uuid.random()}"
            it[Nodes.displayName] = "Test Node"
            it[Nodes.publicIp] = "1.2.3.4"
            it[Nodes.privateIp] = "10.0.0.1"
            it[Nodes.tokenHash] = Uuid.random()
                .toString()
                .replace("-", "")
                .padEnd(64, '0')
            it[Nodes.status] = status
            it[Nodes.health] = health
        }[Nodes.id].let { Uuid.parse(it.toString()) }
    }

    fun createServer(nodeId: Uuid, status: String = "STOPPED"): Uuid = transaction {
        Servers.insert {
            it[Servers.nodeId] = nodeId
            it[Servers.name] = "srv-${Uuid.random()}"
            it[Servers.hostPort] = 25565
            it[Servers.memoryMb] = 1024
            it[Servers.status] = status
        }[Servers.id].let { Uuid.parse(it.toString()) }
    }

    fun serverStatus(serverId: Uuid): String = transaction {
        Servers.selectAll()
            .where { Servers.id eq serverId }
            .first()[Servers.status]
    }

    // -------------------------------------------------------------------------
    // control stream — PENDING node should not emit ACTIVE to agentEvents
    // -------------------------------------------------------------------------

    test("control stream does not emit ACTIVE to nodeStatusFlow for PENDING node") {
        runBlocking {
            val nodeId = createNode(status = "PENDING")

            val agentMessages = flow {
                emit(agentMessage {
                    this.nodeId = nodeId.toString()
                    nodeState = nodeStateSnapshot { }
                })
            }

            val ex = shouldThrow<io.grpc.StatusException> {
                service.control(agentMessages)
                    .collect { }
            }
            ex.status.code shouldBe io.grpc.Status.Code.PERMISSION_DENIED
            (ex.status.description?.contains("pending admin approval") == true) shouldBe true
        }
    }

    test("control stream emits HEALTHY to nodeStatusFlow for ACTIVE node when router is running") {
        runBlocking {
            val nodeId = createNode(status = "ACTIVE")
            val emitted = mutableListOf<AgentEvent.NodeStatusEvent>()

            val collectJob = launch {
                service.agentEvents.filterIsInstance<AgentEvent.NodeStatusEvent>()
                    .collect { emitted.add(it) }
            }

            val agentMessages = flow {
                emit(agentMessage {
                    this.nodeId = nodeId.toString()
                    nodeState = nodeStateSnapshot { routerRunning = true }
                })
            }

            service.control(agentMessages)
                .collect { }

            delay(100.milliseconds)
            collectJob.cancel()

            (emitted.any { it.nodeId == nodeId.toString() && it.health == NodeHealth.HEALTHY }) shouldBe true
        }
    }

    test("control stream emits DEGRADED to nodeStatusFlow for ACTIVE node when router is not running") {
        runBlocking {
            val nodeId = createNode(status = "ACTIVE")
            val emitted = mutableListOf<AgentEvent.NodeStatusEvent>()

            val collectJob = launch {
                service.agentEvents.filterIsInstance<AgentEvent.NodeStatusEvent>()
                    .collect { emitted.add(it) }
            }

            val agentMessages = flow {
                emit(agentMessage {
                    this.nodeId = nodeId.toString()
                    nodeState = nodeStateSnapshot { routerRunning = false }
                })
            }

            service.control(agentMessages)
                .collect { }

            delay(100.milliseconds)
            collectJob.cancel()

            (emitted.any { it.nodeId == nodeId.toString() && it.health == NodeHealth.DEGRADED }) shouldBe true
        }
    }

    test("control stream emits HEALTHY to nodeStatusFlow for UNREACHABLE node on reconnect") {
        runBlocking {
            val nodeId = createNode(status = "ACTIVE", health = "UNREACHABLE")
            val emitted = mutableListOf<AgentEvent.NodeStatusEvent>()

            val collectJob = launch {
                service.agentEvents.filterIsInstance<AgentEvent.NodeStatusEvent>()
                    .collect { emitted.add(it) }
            }

            val agentMessages = flow {
                emit(agentMessage {
                    this.nodeId = nodeId.toString()
                    nodeState = nodeStateSnapshot { routerRunning = true }
                })
            }

            service.control(agentMessages)
                .collect { }

            delay(100.milliseconds)
            collectJob.cancel()

            (emitted.any { it.nodeId == nodeId.toString() && it.health == NodeHealth.HEALTHY }) shouldBe true
        }
    }
})
