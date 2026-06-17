package io.craftpanel.master.grpc

import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.schema.Backups
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.ServerMigrations
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.TestDatabase
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.NodeConnectionStatus
import io.craftpanel.proto.ContainerState
import io.craftpanel.proto.agentMessage
import io.craftpanel.proto.containerState
import io.craftpanel.proto.nodeStateSnapshot
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ControlServiceImplTest : FunSpec({
    val service = ControlServiceImpl(NodeConfig(bootstrapToken = "test-token", agentDataPort = 50052))

    beforeTest {
        TestDatabase.initIfNeeded()
        TestDatabase.reset()
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    fun createNode(status: String = "ACTIVE"): Uuid = transaction {
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

    fun createMigration(nodeId: Uuid, status: String): Uuid = transaction {
        ServerMigrations.insert {
            it[ServerMigrations.serverId] = createServer(nodeId)
            it[ServerMigrations.sourceNodeId] = nodeId
            it[ServerMigrations.targetNodeId] = nodeId
            it[ServerMigrations.status] = status
        }[ServerMigrations.id].let { Uuid.parse(it.toString()) }
    }

    fun createBackup(nodeId: Uuid, serverId: Uuid, status: String): Uuid = transaction {
        Backups.insert {
            it[Backups.serverId] = serverId
            it[Backups.nodeId] = nodeId
            it[Backups.trigger] = "MANUAL"
            it[Backups.status] = status
        }[Backups.id].let { Uuid.parse(it.toString()) }
    }

    fun serverStatus(serverId: Uuid): String = transaction {
        Servers.selectAll()
            .where { Servers.id eq serverId }
            .first()[Servers.status]
    }

    fun nodeStatus(nodeId: Uuid): String = transaction {
        Nodes.selectAll()
            .where { Nodes.id eq nodeId }
            .first()[Nodes.status]
    }

    fun migrationStatus(migrationId: Uuid): String = transaction {
        ServerMigrations.selectAll()
            .where { ServerMigrations.id eq migrationId }
            .first()[ServerMigrations.status]
    }

    fun migrationCompletedAt(migrationId: Uuid) = transaction {
        ServerMigrations.selectAll()
            .where { ServerMigrations.id eq migrationId }
            .first()[ServerMigrations.completedAt]
    }

    fun backupStatus(backupId: Uuid): String = transaction {
        Backups.selectAll()
            .where { Backups.id eq backupId }
            .first()[Backups.status]
    }

    fun backupCompletedAt(backupId: Uuid) = transaction {
        Backups.selectAll()
            .where { Backups.id eq backupId }
            .first()[Backups.completedAt]
    }

    // -------------------------------------------------------------------------
    // reconcileNodeState — status transitions
    // -------------------------------------------------------------------------

    test("reconcile RUNNING in snapshot against STOPPED in DB updates to HEALTHY") {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "STOPPED")

        val snapshot = nodeStateSnapshot {
            containers.add(containerState {
                this.serverId = serverId.toString()
                containerId = "abc123"
                runState = ContainerState.RunState.RUNNING
            })
        }
        service.reconcileNodeState(nodeId.toString(), snapshot)

        serverStatus(serverId) shouldBe "HEALTHY"
    }

    test("reconcile STOPPED in snapshot against HEALTHY in DB updates to STOPPED") {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "HEALTHY")

        val snapshot = nodeStateSnapshot {
            containers.add(containerState {
                this.serverId = serverId.toString()
                containerId = "abc123"
                runState = ContainerState.RunState.STOPPED
            })
        }
        service.reconcileNodeState(nodeId.toString(), snapshot)

        serverStatus(serverId) shouldBe "STOPPED"
    }

    test("reconcile STOPPED in snapshot against STARTING in DB updates to STOPPED") {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "STARTING")

        val snapshot = nodeStateSnapshot {
            containers.add(containerState {
                this.serverId = serverId.toString()
                runState = ContainerState.RunState.STOPPED
            })
        }
        service.reconcileNodeState(nodeId.toString(), snapshot)

        serverStatus(serverId) shouldBe "STOPPED"
    }

    test("reconcile STOPPED in snapshot against UNHEALTHY in DB updates to STOPPED") {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "UNHEALTHY")

        val snapshot = nodeStateSnapshot {
            containers.add(containerState {
                this.serverId = serverId.toString()
                runState = ContainerState.RunState.STOPPED
            })
        }
        service.reconcileNodeState(nodeId.toString(), snapshot)

        serverStatus(serverId) shouldBe "STOPPED"
    }

    test("reconcile EXITED in snapshot updates to UNHEALTHY") {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "HEALTHY")

        val snapshot = nodeStateSnapshot {
            containers.add(containerState {
                this.serverId = serverId.toString()
                runState = ContainerState.RunState.EXITED
            })
        }
        service.reconcileNodeState(nodeId.toString(), snapshot)

        serverStatus(serverId) shouldBe "UNHEALTHY"
    }

    test("reconcile server absent from snapshot with DB HEALTHY updates to STOPPED") {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "HEALTHY")

        service.reconcileNodeState(nodeId.toString(), nodeStateSnapshot { })

        serverStatus(serverId) shouldBe "STOPPED"
    }

    test("reconcile server absent from snapshot with DB STARTING updates to STOPPED") {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "STARTING")

        service.reconcileNodeState(nodeId.toString(), nodeStateSnapshot { })

        serverStatus(serverId) shouldBe "STOPPED"
    }

    test("reconcile server absent from snapshot with DB STOPPED leaves unchanged") {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "STOPPED")

        service.reconcileNodeState(nodeId.toString(), nodeStateSnapshot { })

        serverStatus(serverId) shouldBe "STOPPED"
    }

    test("reconcile RUNNING in snapshot against already HEALTHY in DB leaves unchanged") {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "HEALTHY")

        val snapshot = nodeStateSnapshot {
            containers.add(containerState {
                this.serverId = serverId.toString()
                runState = ContainerState.RunState.RUNNING
            })
        }
        service.reconcileNodeState(nodeId.toString(), snapshot)

        serverStatus(serverId) shouldBe "HEALTHY"
    }

    test("reconcile STOPPED in snapshot against already STOPPED in DB leaves unchanged") {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "STOPPED")

        val snapshot = nodeStateSnapshot {
            containers.add(containerState {
                this.serverId = serverId.toString()
                runState = ContainerState.RunState.STOPPED
            })
        }
        service.reconcileNodeState(nodeId.toString(), snapshot)

        serverStatus(serverId) shouldBe "STOPPED"
    }

    test("reconcile does not promote PENDING node to ACTIVE - admin trust required") {
        val nodeId = createNode(status = "PENDING")

        service.reconcileNodeState(nodeId.toString(), nodeStateSnapshot { })

        nodeStatus(nodeId) shouldBe "PENDING"
    }

    test("reconcile promotes DEGRADED node to ACTIVE on reconnect") {
        val nodeId = createNode(status = "DEGRADED")

        service.reconcileNodeState(nodeId.toString(), nodeStateSnapshot { })

        nodeStatus(nodeId) shouldBe "ACTIVE"
    }

    test("reconcile leaves ACTIVE node status unchanged") {
        val nodeId = createNode(status = "ACTIVE")

        service.reconcileNodeState(nodeId.toString(), nodeStateSnapshot { })

        nodeStatus(nodeId) shouldBe "ACTIVE"
    }

    test("reconcile ignores invalid node ID") {
        service.reconcileNodeState("not-a-uuid", nodeStateSnapshot { })
    }

    // -------------------------------------------------------------------------
    // markNodeDegraded
    // -------------------------------------------------------------------------

    test("markNodeDegraded sets node status to DEGRADED") {
        val nodeId = createNode(status = "ACTIVE")

        service.markNodeDegraded(nodeId.toString())

        nodeStatus(nodeId) shouldBe "DEGRADED"
    }

    test("markNodeDegraded sets HEALTHY servers to UNHEALTHY") {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "HEALTHY")

        service.markNodeDegraded(nodeId.toString())

        serverStatus(serverId) shouldBe "UNHEALTHY"
    }

    test("markNodeDegraded sets STARTING servers to UNHEALTHY") {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "STARTING")

        service.markNodeDegraded(nodeId.toString())

        serverStatus(serverId) shouldBe "UNHEALTHY"
    }

    test("markNodeDegraded leaves STOPPED servers unchanged") {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "STOPPED")

        service.markNodeDegraded(nodeId.toString())

        serverStatus(serverId) shouldBe "STOPPED"
    }

    test("markNodeDegraded sets PENDING migration to FAILED and sets completedAt") {
        val nodeId = createNode()
        val migrationId = createMigration(nodeId, status = "PENDING")

        service.markNodeDegraded(nodeId.toString())

        migrationStatus(migrationId) shouldBe "FAILED"
        migrationCompletedAt(migrationId) shouldNotBe null
    }

    test("markNodeDegraded sets SYNCING migration to FAILED and sets completedAt") {
        val nodeId = createNode()
        val migrationId = createMigration(nodeId, status = "SYNCING")

        service.markNodeDegraded(nodeId.toString())

        migrationStatus(migrationId) shouldBe "FAILED"
        migrationCompletedAt(migrationId) shouldNotBe null
    }

    test("markNodeDegraded leaves COMPLETED migration unchanged") {
        val nodeId = createNode()
        val migrationId = createMigration(nodeId, status = "COMPLETED")

        service.markNodeDegraded(nodeId.toString())

        migrationStatus(migrationId) shouldBe "COMPLETED"
        migrationCompletedAt(migrationId) shouldBe null
    }

    test("markNodeDegraded sets IN_PROGRESS backup to FAILED and sets completedAt") {
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        val backupId = createBackup(nodeId, serverId, status = "IN_PROGRESS")

        service.markNodeDegraded(nodeId.toString())

        backupStatus(backupId) shouldBe "FAILED"
        backupCompletedAt(backupId) shouldNotBe null
    }

    test("markNodeDegraded leaves COMPLETED backup unchanged") {
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        val backupId = createBackup(nodeId, serverId, status = "COMPLETED")

        service.markNodeDegraded(nodeId.toString())

        backupStatus(backupId) shouldBe "COMPLETED"
        backupCompletedAt(backupId) shouldBe null
    }

    test("markNodeDegraded leaves FAILED backup unchanged") {
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        val backupId = createBackup(nodeId, serverId, status = "FAILED")

        service.markNodeDegraded(nodeId.toString())

        backupStatus(backupId) shouldBe "FAILED"
    }

    test("markNodeDegraded ignores invalid node ID") {
        service.markNodeDegraded("not-a-uuid")
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

    test("control stream emits ACTIVE to nodeStatusFlow for ACTIVE node") {
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
                    nodeState = nodeStateSnapshot { }
                })
            }

            service.control(agentMessages)
                .collect { }

            delay(100.milliseconds)
            collectJob.cancel()

            (emitted.any { it.nodeId == nodeId.toString() && it.status == NodeConnectionStatus.ACTIVE }) shouldBe true
        }
    }

    test("control stream emits ACTIVE to nodeStatusFlow for DEGRADED node on reconnect") {
        runBlocking {
            val nodeId = createNode(status = "DEGRADED")
            val emitted = mutableListOf<AgentEvent.NodeStatusEvent>()

            val collectJob = launch {
                service.agentEvents.filterIsInstance<AgentEvent.NodeStatusEvent>()
                    .collect { emitted.add(it) }
            }

            val agentMessages = flow {
                emit(agentMessage {
                    this.nodeId = nodeId.toString()
                    nodeState = nodeStateSnapshot { }
                })
            }

            service.control(agentMessages)
                .collect { }

            delay(100.milliseconds)
            collectJob.cancel()

            (emitted.any { it.nodeId == nodeId.toString() && it.status == NodeConnectionStatus.ACTIVE }) shouldBe true
        }
    }
})
