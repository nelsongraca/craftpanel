package io.craftpanel.master.service.migration

import io.craftpanel.master.TestAgentGateway
import io.craftpanel.master.TestDatabase
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.service.ContainerLifecycle
import io.craftpanel.master.service.ModService
import io.craftpanel.master.service.repo.NodeRepositoryImpl
import io.craftpanel.master.service.repo.NodeRow
import io.craftpanel.master.service.repo.ServerRepositoryImpl
import io.craftpanel.master.service.repo.ServerRow
import io.craftpanel.master.service.repo.NetworkRepositoryImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class MigrationRunnerTest : FunSpec({
    lateinit var nodeId: Uuid
    lateinit var targetNodeId: Uuid
    lateinit var serverId: Uuid

    fun createCtx(migrationId: Uuid = Uuid.random()): MigrationContext {
        return MigrationContext(
            migrationId = migrationId,
            migrationIdStr = migrationId.toString(),
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
                id = targetNodeId, displayName = "target", hostname = "target",
                publicIp = "5.6.7.8", privateIp = "10.0.0.2", tokenHash = "",
                status = "ACTIVE", health = "HEALTHY", totalRamMb = 16384, totalCpuShares = 0,
                systemRamUsedMb = null, portRangeStart = 25565, portRangeEnd = 25600,
                swarmActive = false, agentVersion = null, lastSeenAt = null,
                createdAt = "", updatedAt = "",
            ),
            targetPrivateIp = "10.0.0.2",
            gateway = TestAgentGateway(agentEvents = MutableSharedFlow()),
            serverRepository = ServerRepositoryImpl(),
            nodeRepository = NodeRepositoryImpl(),
            networkRepository = NetworkRepositoryImpl(),
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

    beforeTest {
        TestDatabase.initIfNeeded()
        TestDatabase.reset()
        nodeId = transaction {
            Nodes.insert {
                it[Nodes.hostname] = "source-node"
                it[Nodes.displayName] = "source-node"
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
        targetNodeId = transaction {
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
    }

    test("all steps succeed - status COMPLETED") {
        runTest {
            val baseCtx = createCtx()
            val migration = baseCtx.serverRepository.createMigration(serverId, nodeId, targetNodeId)
            val runCtx = createCtx(migration.id)

            val allSuccess = object : MigrationStep {
                override val stepNumber = 1
                override val description = "Always succeeds"
                override suspend fun execute(ctx: MigrationContext) = StepResult.Success
            }

            MigrationRunner(listOf(allSuccess), runCtx).run()
            val row = runCtx.serverRepository.findMigrationById(runCtx.migrationId)
            row?.status shouldBe MigrationStatus.COMPLETED.name
        }
    }

    test("step fails - runner stops and remaining steps not executed") {
        runTest {
            var secondExecuted = false
            val baseCtx = createCtx()
            val migration = baseCtx.serverRepository.createMigration(serverId, nodeId, targetNodeId)
            val runCtx = createCtx(migration.id)

            val steps = listOf(
                object : MigrationStep {
                    override val stepNumber = 1
                    override val description = "Fails"
                    override suspend fun execute(ctx: MigrationContext) = StepResult.Failure("Step 1 failed")
                },
                object : MigrationStep {
                    override val stepNumber = 2
                    override val description = "Should not run"
                    override suspend fun execute(ctx: MigrationContext): StepResult {
                        secondExecuted = true
                        return StepResult.Success
                    }
                },
            )

            MigrationRunner(steps, runCtx).run()
            val row = runCtx.serverRepository.findMigrationById(runCtx.migrationId)
            row?.status shouldBe MigrationStatus.FAILED.name
            secondExecuted shouldBe false
        }
    }

    test("finally block cleans up rsync port even on failure") {
        runTest {
            val baseCtx = createCtx()
            val migration = baseCtx.serverRepository.createMigration(serverId, nodeId, targetNodeId)
            val runCtx = createCtx(migration.id)
            runCtx.rsyncPort = 25566
            runCtx.serverRepository.registerPort(runCtx.targetNodeId, 25566, "TCP", null)

            val failing = object : MigrationStep {
                override val stepNumber = 1
                override val description = "Fails"
                override suspend fun execute(ctx: MigrationContext) = StepResult.Failure("fail")
            }

            MigrationRunner(listOf(failing), runCtx).run()
            val usedPorts = runCtx.serverRepository.findUsedPortsOnNode(runCtx.targetNodeId)
            usedPorts shouldBe emptyList()
        }
    }
})
