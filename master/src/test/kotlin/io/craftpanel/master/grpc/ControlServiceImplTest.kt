package io.craftpanel.master.grpc

import io.craftpanel.master.TestAgentGateway
import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.NodeHealth
import io.craftpanel.master.grpc.handlers.*
import io.craftpanel.master.service.NodeStateReconciler
import io.craftpanel.master.service.repo.FakeNodeRepository
import io.craftpanel.master.service.repo.impl.NodeRepositoryImpl
import io.craftpanel.proto.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

class ControlServiceImplTest :
    FunSpec({
        val repos = TestRepositories()
        val nodeRepository = NodeRepositoryImpl()
        val reconciler = NodeStateReconciler(repos.serverRepository, nodeRepository, repos.migrationRepository, repos.backupRepository)
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
            nodeRepository = nodeRepository,
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
            serverRepository = repos.serverRepository,
            backupRepository = repos.backupRepository
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
                    emit(
                        agentMessage {
                            this.nodeId = nodeId.toString()
                            nodeState = nodeStateSnapshot { }
                        }
                    )
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
                    emit(
                        agentMessage {
                            this.nodeId = nodeId.toString()
                            nodeState = nodeStateSnapshot { routerRunning = true }
                        }
                    )
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
                    emit(
                        agentMessage {
                            this.nodeId = nodeId.toString()
                            nodeState = nodeStateSnapshot { routerRunning = false }
                        }
                    )
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
                    emit(
                        agentMessage {
                            this.nodeId = nodeId.toString()
                            nodeState = nodeStateSnapshot { routerRunning = true }
                        }
                    )
                }

                service.control(agentMessages)
                    .collect { }

                delay(100.milliseconds)
                collectJob.cancel()

                (emitted.any { it.nodeId == nodeId.toString() && it.health == NodeHealth.HEALTHY }) shouldBe true
            }
        }

        // -------------------------------------------------------------------------
        // registration / identify / verify — routed through NodeRepository seam,
        // no live DB required (proves the deepening: ControlServiceImpl no longer
        // touches Exposed directly).
        // -------------------------------------------------------------------------

        fun buildFakeService(fakeRepo: FakeNodeRepository): ControlServiceImpl {
            val fakeAgentEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 1024)
            val fakeRepos = TestRepositories()
            val fakeReconciler = NodeStateReconciler(fakeRepos.serverRepository, fakeRepo, fakeRepos.migrationRepository, fakeRepos.backupRepository)
            val fakeDataOpContext = DataOpContext(ConcurrentHashMap(), ConcurrentHashMap())
            return ControlServiceImpl(
                nodeConfig = NodeConfig(bootstrapToken = "test-token", agentDataPort = 50052),
                nodeStateReconciler = fakeReconciler,
                nodeRepository = fakeRepo,
                agentEventsFlow = fakeAgentEvents,
                dataOpContext = fakeDataOpContext,
                nodeStateHandler = NodeStateHandler(fakeAgentEvents, fakeReconciler),
                nodeMetricsHandler = NodeMetricsHandler(fakeAgentEvents, fakeReconciler),
                containerMetricsHandler = ContainerMetricsHandler(fakeAgentEvents),
                serverStatusHandler = ServerStatusHandler(fakeAgentEvents),
                playerUpdateHandler = PlayerUpdateHandler(fakeAgentEvents),
                backupHandler = BackupHandler(fakeAgentEvents),
                migrationHandler = MigrationHandler(fakeAgentEvents),
                dataOpResponseHandler = DataOpResponseHandler(fakeDataOpContext),
                serverRepository = fakeRepos.serverRepository,
                backupRepository = fakeRepos.backupRepository
            )
        }

        test("registerNode creates a PENDING node via NodeRepository (no live DB)") {
            runBlocking {
                val fakeRepo = FakeNodeRepository()
                val fakeService = buildFakeService(fakeRepo)

                val response = fakeService.registerNode(
                    registerNodeRequest {
                        bootstrapToken = "test-token"
                        metadata = nodeMetadata {
                            hostname = "fake-node"
                            publicIp = "1.2.3.4"
                            privateIp = "10.0.0.9"
                            totalRamMb = 2048
                            totalCpuShares = 1024
                            agentVersion = "1.0.0"
                        }
                    }
                )

                val stored = fakeRepo.findById(Uuid.parse(response.nodeId))
                stored.shouldNotBeNull()
                stored.status shouldBe "PENDING"
                stored.hostname shouldBe "fake-node"
                stored.totalRamMb shouldBe 2048
                stored.totalCpuShares shouldBe 1024
                stored.agentVersion shouldBe "1.0.0"
            }
        }

        test("identifyNode reports ACTIVE for a trusted node and updates lastSeen/privateIp via NodeRepository") {
            runBlocking {
                val fakeRepo = FakeNodeRepository()
                val fakeService = buildFakeService(fakeRepo)
                val rawKey = "raw-node-key"
                val keyHash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(rawKey.toByteArray())
                    .let {
                        java.util.HexFormat.of()
                            .formatHex(it)
                    }

                val created = fakeRepo.create(
                    displayName = "n",
                    hostname = "n",
                    publicIp = "1.1.1.1",
                    privateIp = "10.0.0.1",
                    tokenHash = keyHash,
                    portRangeStart = 25570,
                    portRangeEnd = 26070
                )
                fakeRepo.updateStatus(created.id, io.craftpanel.master.domain.NodeStatus.ACTIVE)

                val response = fakeService.identifyNode(
                    identifyNodeRequest {
                        nodeKey = rawKey
                        metadata = nodeMetadata {
                            publicIp = "9.9.9.9"
                            privateIp = "10.0.0.42"
                        }
                    }
                )

                response.status shouldBe IdentifyNodeResponse.IdentifyStatus.ACTIVE
                response.nodeId shouldBe created.id.toString()

                val updated = fakeRepo.findById(created.id)
                updated.shouldNotBeNull()
                updated.publicIp shouldBe "9.9.9.9"
                updated.privateIp shouldBe "10.0.0.42"
                updated.lastSeenAt.shouldNotBeNull()
            }
        }

        test("verifyNodeKey returns false when no node matches the token hash (no live DB)") {
            val fakeRepo = FakeNodeRepository()
            val fakeService = buildFakeService(fakeRepo)

            fakeService.verifyNodeKey("some-random-key") shouldBe false
        }
    })
