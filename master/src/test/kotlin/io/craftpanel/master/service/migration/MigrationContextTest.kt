package io.craftpanel.master.service.migration

import io.craftpanel.master.TestAgentGateway
import io.craftpanel.master.TestDatabase
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.domain.MigrationStepStatus
import io.craftpanel.master.service.ContainerLifecycle
import io.craftpanel.master.service.ModService
import io.craftpanel.master.service.PortExhaustedException
import io.craftpanel.master.service.repo.NodeRepositoryImpl
import io.craftpanel.master.service.repo.NodeRow
import io.craftpanel.master.service.repo.ServerRepositoryImpl
import io.craftpanel.master.service.repo.ServerRow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class MigrationContextTest : FunSpec({
    lateinit var nodeId: Uuid
    lateinit var serverId: Uuid
    lateinit var ctx: MigrationContext

    beforeTest {
        TestDatabase.initIfNeeded()
        TestDatabase.reset()
        nodeId = transaction {
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
                it[Servers.status] = "STOPPED"
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }
        val targetNodeId = transaction {
            Nodes.insert {
                it[Nodes.hostname] = "target-node"
                it[Nodes.displayName] = "target-node"
                it[Nodes.publicIp] = "5.6.7.8"
                it[Nodes.privateIp] = "10.0.0.2"
                it[Nodes.tokenHash] = "b".repeat(64)
                it[Nodes.status] = "ACTIVE"
                it[Nodes.totalRamMb] = 16384
                it[Nodes.totalCpuShares] = 0
                it[Nodes.portRangeStart] = 25565
                it[Nodes.portRangeEnd] = 25600
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }
        ctx = MigrationContext(
            migrationId = Uuid.random(),
            migrationIdStr = Uuid.random().toString(),
            serverId = serverId,
            serverIdStr = serverId.toString(),
            sourceNodeId = nodeId,
            sourceNodeIdStr = nodeId.toString(),
            targetNodeId = targetNodeId,
            targetNodeIdStr = targetNodeId.toString(),
            rsyncImage = "alpine",
            playerWarningMessage = "Server restarting",
            containerNamePrefix = "craftpanel",
            serverRow = ServerRow(
                id = serverId, name = "test-server", displayName = "test-server",
                description = null, nodeId = nodeId, networkId = null,
                serverType = "VANILLA", mcVersion = "1.21.4", status = "STOPPED",
                hostPort = 25565, memoryMb = 1024, cpuShares = 0,
                exposedExternally = false, publicSubdomain = null,
                dnsRecordId = null, dnsRecordName = null, customHostname = null,
                configMode = "MANAGED", stopCommand = "stop", itzgImageTag = "latest",
                needsRecreate = false, backupSchedule = null, backupMaxCount = 0,
                backupScheduleLastFired = null, lastPlayerCount = null,
                lastPlayerNames = null, lastPlayerUpdate = null, lastSeenAt = null,
                createdAt = "", updatedAt = "",
            ),
            targetNodeRow = NodeRow(
                id = targetNodeId, displayName = "target-node", hostname = "target-node",
                publicIp = "5.6.7.8", privateIp = "10.0.0.2", tokenHash = "",
                status = "ACTIVE", health = "HEALTHY", totalRamMb = 16384, totalCpuShares = 0,
                systemRamUsedMb = null, portRangeStart = 25565, portRangeEnd = 25600,
                swarmActive = false, agentVersion = null, lastSeenAt = null,
                createdAt = "", updatedAt = "",
            ),
            targetPrivateIp = "10.0.0.2",
            gateway = TestAgentGateway(),
            serverRepository = ServerRepositoryImpl(),
            nodeRepository = NodeRepositoryImpl(),
            dnsProvider = null,
            lifecycle = ContainerLifecycle(
                gateway = TestAgentGateway(),
                modService = ModService(ServerRepositoryImpl()),
                serverRepository = ServerRepositoryImpl(),
            ),
            scope = TestScope(),
            eventFlow = MutableSharedFlow(),
        )
    }

    test("startStep creates step and returns step id") {
        runTest {
            val migration = ctx.serverRepository.createMigration(ctx.serverId, ctx.sourceNodeId, ctx.targetNodeId)
            val ctx2 = ctx.copy(migrationId = migration.id, migrationIdStr = migration.id.toString())
            val stepId = ctx2.startStep(1, "Test step")
            stepId.shouldNotBeNull()
            val steps = ctx2.serverRepository.listMigrationSteps(ctx2.migrationId)
            steps.size shouldBe 1
            steps[0].stepNumber shouldBe 1
            steps[0].description shouldBe "Test step"
            steps[0].status shouldBe MigrationStepStatus.RUNNING.name
        }
    }

    test("completeStep marks step success") {
        runTest {
            val migration = ctx.serverRepository.createMigration(ctx.serverId, ctx.sourceNodeId, ctx.targetNodeId)
            val ctx2 = ctx.copy(migrationId = migration.id, migrationIdStr = migration.id.toString())
            val stepId = ctx2.startStep(1, "Test step")
            ctx2.completeStep(stepId, true)
            val step = ctx2.serverRepository.listMigrationSteps(ctx2.migrationId).first()
            step.status shouldBe MigrationStepStatus.SUCCESS.name
        }
    }

    test("completeStep marks step failure with error") {
        runTest {
            val migration = ctx.serverRepository.createMigration(ctx.serverId, ctx.sourceNodeId, ctx.targetNodeId)
            val ctx2 = ctx.copy(migrationId = migration.id, migrationIdStr = migration.id.toString())
            val stepId = ctx2.startStep(1, "Test step")
            ctx2.completeStep(stepId, false, "Something went wrong")
            val step = ctx2.serverRepository.listMigrationSteps(ctx2.migrationId).first()
            step.status shouldBe MigrationStepStatus.FAILED.name
            step.errorMessage shouldBe "Something went wrong"
        }
    }

    test("failMigration sets FAILED status") {
        runTest {
            val migration = ctx.serverRepository.createMigration(ctx.serverId, ctx.sourceNodeId, ctx.targetNodeId)
            val ctx2 = ctx.copy(migrationId = migration.id, migrationIdStr = migration.id.toString())
            ctx2.failMigration("Test error")
            val row = ctx2.serverRepository.findMigrationById(ctx2.migrationId)
            row.shouldNotBeNull()
            row.status shouldBe MigrationStatus.FAILED.name
        }
    }

    test("restartSource does nothing when sourceStopped is false") {
        runTest {
            ctx.sourceStopped = false
            ctx.restartSource()
        }
    }

    test("allocateRsyncPort allocates first free port") {
        runTest {
            val port = ctx.allocateRsyncPort()
            port shouldBe 25565
        }
    }

    test("allocateRsyncPort throws when port range exhausted") {
        runTest {
            for (i in 25565..25600) {
                ctx.serverRepository.registerPort(ctx.targetNodeId, i, "TCP", null)
            }
            shouldThrow<PortExhaustedException> {
                ctx.allocateRsyncPort()
            }
        }
    }
})
