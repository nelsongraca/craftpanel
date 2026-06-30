package io.craftpanel.master.service.migration

import io.craftpanel.master.TestAgentGateway
import io.craftpanel.master.TestDatabase
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.service.ContainerLifecycle
import io.craftpanel.master.service.ModService
import io.craftpanel.master.service.migration.steps.AllocateRsyncPortStep
import io.craftpanel.master.service.repo.NodeRepositoryImpl
import io.craftpanel.master.service.repo.NodeRow
import io.craftpanel.master.service.repo.ServerRepositoryImpl
import io.craftpanel.master.service.repo.ServerRow
import io.craftpanel.master.service.repo.NetworkRepositoryImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class AllocateRsyncPortStepTest : FunSpec({
    lateinit var nodeId: Uuid
    lateinit var ctx: MigrationContext

    beforeTest {
        TestDatabase.initIfNeeded()
        TestDatabase.reset()
        nodeId = transaction {
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
            serverId = Uuid.random(),
            serverIdStr = Uuid.random().toString(),
            sourceNodeId = Uuid.random(),
            sourceNodeIdStr = Uuid.random().toString(),
            targetNodeId = nodeId,
            targetNodeIdStr = nodeId.toString(),
            rsyncImage = "alpine",
            playerWarningMessage = "Server restarting",
            containerNamePrefix = "craftpanel",
            serverRow = ServerRow(
                id = Uuid.random(), name = "test", displayName = "test",
                description = null, nodeId = Uuid.random(), networkId = null,
                serverType = "VANILLA", mcVersion = "1.21.4", status = "RUNNING",
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
                id = nodeId, displayName = "target-node", hostname = "target-node",
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

    test("returns Success and registers port when port available") {
        runTest {
            val step = AllocateRsyncPortStep()
            val result = step.execute(ctx)
            result.shouldBeInstanceOf<StepResult.Success>()
            ctx.rsyncPort shouldBe 25565
        }
    }

    test("returns Success with next port when first port taken") {
        runTest {
            ctx.serverRepository.registerPort(ctx.targetNodeId, 25565, "TCP", null)
            val step = AllocateRsyncPortStep()
            val result = step.execute(ctx)
            result.shouldBeInstanceOf<StepResult.Success>()
            ctx.rsyncPort shouldBe 25566
        }
    }

    test("returns Failure when port range exhausted") {
        runTest {
            for (i in 25565..25600) {
                ctx.serverRepository.registerPort(ctx.targetNodeId, i, "TCP", null)
            }
            val step = AllocateRsyncPortStep()
            val result = step.execute(ctx)
            result.shouldBeInstanceOf<StepResult.Failure>()
            (result as StepResult.Failure).error shouldBe "Port allocation failed: No free ports in range 25565-25600 on node ${ctx.targetNodeId}"
        }
    }
})
