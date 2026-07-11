package io.craftpanel.master.service

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.AlertRepositoryImpl
import io.craftpanel.master.service.repo.NodeRepositoryImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

class NodeObserverTest :
    FunSpec({
        val repos = TestRepositories()
        val nodeRepository = NodeRepositoryImpl()
        val alertRepository = AlertRepositoryImpl()
        lateinit var serverId: Uuid

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
            val nodeId = transaction {
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
                }[Nodes.id]
            }
            serverId = transaction {
                Servers.insert {
                    it[Servers.nodeId] = nodeId
                    it[Servers.name] = "test-server"
                    it[Servers.displayName] = "test-server"
                    it[Servers.serverType] = "VANILLA"
                    it[Servers.mcVersion] = "1.21.4"
                    it[Servers.itzgImageTag] = "latest"
                    it[Servers.hostPort] = 25565
                    it[Servers.memoryMb] = 1024
                    it[Servers.cpuShares] = 0
                    it[Servers.status] = "HEALTHY"
                }[Servers.id]
            }
        }

        fun dbStatus(): String = transaction {
            Servers.selectAll().where { Servers.id eq serverId }.first()[Servers.status]
        }

        fun observer(restartManager: ServerRestartManager?, crashRestarts: Channel<Uuid>, events: MutableSharedFlow<AgentEvent>) = NodeObserver(
            agentEvents = events,
            restartManager = restartManager,
            crashRestarts = crashRestarts,
            emitAgentEvent = {},
            serverRepository = repos.serverRepository,
            nodeRepository = nodeRepository,
            containerMetricsRepository = repos.containerMetricsRepository,
            backupRepository = repos.backupRepository,
            alertEvaluator = AlertEvaluator(alertRepository)
        )

        test("HEALTHY to UNHEALTHY transition triggers a crash restart") {
            runTest {
                val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
                val crashRestarts = Channel<Uuid>(Channel.BUFFERED)
                val restartManager = ServerRestartManager(maxAttempts = 3, windowSeconds = 3600)
                val job = observer(restartManager, crashRestarts, events).start(this)
                delay(50.milliseconds)

                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.UNHEALTHY))
                delay(50.milliseconds)

                dbStatus() shouldBe "UNHEALTHY"
                crashRestarts.tryReceive().getOrNull() shouldBe serverId
                job.cancel()
            }
        }

        test("intentional stop (STOPPED written before death) does not trigger a restart") {
            runTest {
                val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
                val crashRestarts = Channel<Uuid>(Channel.BUFFERED)
                val restartManager = ServerRestartManager(maxAttempts = 3, windowSeconds = 3600)
                repos.serverRepository.updateStatus(serverId, "STOPPED", null)
                val job = observer(restartManager, crashRestarts, events).start(this)
                delay(50.milliseconds)

                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.UNHEALTHY))
                delay(50.milliseconds)

                dbStatus() shouldBe "UNHEALTHY"
                crashRestarts.tryReceive().getOrNull() shouldBe null
                job.cancel()
            }
        }

        test("reaching HEALTHY resets the crash counter") {
            runTest {
                val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
                val crashRestarts = Channel<Uuid>(Channel.BUFFERED)
                val restartManager = ServerRestartManager(maxAttempts = 1, windowSeconds = 3600)
                val job = observer(restartManager, crashRestarts, events).start(this)
                delay(50.milliseconds)

                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.UNHEALTHY))
                delay(50.milliseconds)
                crashRestarts.tryReceive().getOrNull() shouldBe serverId

                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.HEALTHY))
                delay(50.milliseconds)
                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.UNHEALTHY))
                delay(50.milliseconds)

                crashRestarts.tryReceive().getOrNull() shouldBe serverId
                job.cancel()
            }
        }

        test("crash cap reached leaves the server UNHEALTHY without further restarts") {
            runTest {
                val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
                val crashRestarts = Channel<Uuid>(Channel.BUFFERED)
                val restartManager = ServerRestartManager(maxAttempts = 1, windowSeconds = 3600)
                val job = observer(restartManager, crashRestarts, events).start(this)
                delay(50.milliseconds)

                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.STARTING))
                delay(50.milliseconds)
                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.UNHEALTHY))
                delay(50.milliseconds)
                crashRestarts.tryReceive().getOrNull() shouldBe serverId

                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.STARTING))
                delay(50.milliseconds)
                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.UNHEALTHY))
                delay(50.milliseconds)

                dbStatus() shouldBe "UNHEALTHY"
                crashRestarts.tryReceive().getOrNull() shouldBe null
                job.cancel()
            }
        }

        test("null restartManager disables crash restart entirely") {
            runTest {
                val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
                val crashRestarts = Channel<Uuid>(Channel.BUFFERED)
                val job = observer(null, crashRestarts, events).start(this)
                delay(50.milliseconds)

                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.UNHEALTHY))
                delay(50.milliseconds)

                dbStatus() shouldBe "UNHEALTHY"
                crashRestarts.tryReceive().getOrNull() shouldBe null
                job.cancel()
            }
        }
    })
