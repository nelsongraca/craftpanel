package io.craftpanel.master.grpc

import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.schema.Backups
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.ServerMigrations
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.TestDatabase
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.NodeHealth
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

    fun nodeHealth(nodeId: Uuid): String = transaction {
        Nodes.selectAll()
            .where { Nodes.id eq nodeId }
            .first()[Nodes.health]
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

    test("reconcile clears UNREACHABLE health to HEALTHY on reconnect when router is running") {
        val nodeId = createNode(status = "ACTIVE", health = "UNREACHABLE")

        service.reconcileNodeState(nodeId.toString(), nodeStateSnapshot { routerRunning = true })

        nodeStatus(nodeId) shouldBe "ACTIVE"
        nodeHealth(nodeId) shouldBe "HEALTHY"
    }

    test("reconcile sets health to DEGRADED on reconnect when router is not running") {
        val nodeId = createNode(status = "ACTIVE", health = "UNREACHABLE")

        service.reconcileNodeState(nodeId.toString(), nodeStateSnapshot { routerRunning = false })

        nodeStatus(nodeId) shouldBe "ACTIVE"
        nodeHealth(nodeId) shouldBe "DEGRADED"
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
    // markNodeUnreachable
    // -------------------------------------------------------------------------

    test("markNodeUnreachable sets node health to UNREACHABLE, leaves status ACTIVE") {
        val nodeId = createNode(status = "ACTIVE")

        service.markNodeUnreachable(nodeId.toString())

        nodeStatus(nodeId) shouldBe "ACTIVE"
        nodeHealth(nodeId) shouldBe "UNREACHABLE"
    }

    test("markNodeUnreachable leaves HEALTHY servers unchanged") {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "HEALTHY")

        service.markNodeUnreachable(nodeId.toString())

        serverStatus(serverId) shouldBe "HEALTHY"
    }

    test("markNodeUnreachable leaves STARTING servers unchanged") {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "STARTING")

        service.markNodeUnreachable(nodeId.toString())

        serverStatus(serverId) shouldBe "STARTING"
    }

    test("markNodeUnreachable leaves STOPPED servers unchanged") {
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "STOPPED")

        service.markNodeUnreachable(nodeId.toString())

        serverStatus(serverId) shouldBe "STOPPED"
    }

    test("markNodeUnreachable sets PENDING migration to FAILED and sets completedAt") {
        val nodeId = createNode()
        val migrationId = createMigration(nodeId, status = "PENDING")

        service.markNodeUnreachable(nodeId.toString())

        migrationStatus(migrationId) shouldBe "FAILED"
        migrationCompletedAt(migrationId) shouldNotBe null
    }

    test("markNodeUnreachable sets SYNCING migration to FAILED and sets completedAt") {
        val nodeId = createNode()
        val migrationId = createMigration(nodeId, status = "SYNCING")

        service.markNodeUnreachable(nodeId.toString())

        migrationStatus(migrationId) shouldBe "FAILED"
        migrationCompletedAt(migrationId) shouldNotBe null
    }

    test("markNodeUnreachable leaves COMPLETED migration unchanged") {
        val nodeId = createNode()
        val migrationId = createMigration(nodeId, status = "COMPLETED")

        service.markNodeUnreachable(nodeId.toString())

        migrationStatus(migrationId) shouldBe "COMPLETED"
        migrationCompletedAt(migrationId) shouldBe null
    }

    test("markNodeUnreachable sets IN_PROGRESS backup to FAILED and sets completedAt") {
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        val backupId = createBackup(nodeId, serverId, status = "IN_PROGRESS")

        service.markNodeUnreachable(nodeId.toString())

        backupStatus(backupId) shouldBe "FAILED"
        backupCompletedAt(backupId) shouldNotBe null
    }

    test("markNodeUnreachable leaves COMPLETED backup unchanged") {
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        val backupId = createBackup(nodeId, serverId, status = "COMPLETED")

        service.markNodeUnreachable(nodeId.toString())

        backupStatus(backupId) shouldBe "COMPLETED"
        backupCompletedAt(backupId) shouldBe null
    }

    test("markNodeUnreachable leaves FAILED backup unchanged") {
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        val backupId = createBackup(nodeId, serverId, status = "FAILED")

        service.markNodeUnreachable(nodeId.toString())

        backupStatus(backupId) shouldBe "FAILED"
    }

    test("markNodeUnreachable ignores invalid node ID") {
        service.markNodeUnreachable("not-a-uuid")
    }

    test("markNodeUnreachable skips non-ACTIVE node") {
        val nodeId = createNode(status = "PENDING")

        service.markNodeUnreachable(nodeId.toString())

        nodeHealth(nodeId) shouldBe "HEALTHY"
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
