package io.craftpanel.master.service

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.master.service.repo.impl.AlertRepositoryImpl
import io.craftpanel.master.service.repo.impl.NodeRepositoryImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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
                    it[Nodes.totalCpuMillicores] = 0
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
                    it[Servers.cpuLimitMillicores] = 0
                    it[Servers.status] = "HEALTHY"
                }[Servers.id].value
            }
        }

        fun dbStatus(): String = transaction {
            Servers.selectAll()
                .where { Servers.id eq serverId }
                .first()[Servers.status]
        }

        fun dbRestartPending(): Boolean = transaction {
            Servers.selectAll()
                .where { Servers.id eq serverId }
                .first()[Servers.restartPending]
        }

        fun setRestartPending(value: Boolean) = transaction {
            Servers.update({ Servers.id eq serverId }) { it[Servers.restartPending] = value }
        }

        fun observer(events: MutableSharedFlow<AgentEvent>) = NodeObserver(
            agentEvents = events,
            emitAgentEvent = {},
            serverRepository = repos.serverRepository,
            nodeRepository = nodeRepository,
            containerMetricsRepository = repos.containerMetricsRepository,
            backupRepository = repos.backupRepository,
            alertEvaluator = AlertEvaluator(alertRepository)
        )

        test("persists status from ServerStatusEvent") {
            runTest {
                val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
                val job = observer(events).start(this)
                delay(50.milliseconds)

                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.UNHEALTHY))
                delay(50.milliseconds)

                dbStatus() shouldBe "UNHEALTHY"
                job.cancel()
            }
        }

        test("persists STOPPED from ServerStatusEvent") {
            runTest {
                val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
                val job = observer(events).start(this)
                delay(50.milliseconds)

                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.STOPPED))
                delay(50.milliseconds)

                dbStatus() shouldBe "STOPPED"
                job.cancel()
            }
        }

        test("clears restart_pending on a STARTING transition") {
            runTest {
                setRestartPending(true)
                val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
                val job = observer(events).start(this)
                delay(50.milliseconds)

                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.STARTING))
                delay(50.milliseconds)

                dbRestartPending() shouldBe false
                job.cancel()
            }
        }

        test("keeps restart_pending on a HEALTHY reaffirm (no STARTING)") {
            runTest {
                setRestartPending(true)
                val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
                val job = observer(events).start(this)
                delay(50.milliseconds)

                events.emit(AgentEvent.ServerStatusEvent(serverId.toString(), ServerStatus.HEALTHY))
                delay(50.milliseconds)

                dbRestartPending() shouldBe true
                job.cancel()
            }
        }
    })
