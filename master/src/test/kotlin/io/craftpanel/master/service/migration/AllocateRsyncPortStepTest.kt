package io.craftpanel.master.service.migration
import io.craftpanel.master.*
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.*
import io.craftpanel.master.service.migration.steps.AllocateRsyncPortStep
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class AllocateRsyncPortStepTest :
    FunSpec({
        lateinit var nodeId: Uuid
        lateinit var plan: MigrationPlan
        lateinit var coord: MigrationCoordinator

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
            plan = MigrationPlan(
                migrationId = Uuid.random(),
                migrationIdStr = Uuid.random()
                    .toString(),
                serverId = Uuid.random(),
                serverIdStr = Uuid.random()
                    .toString(),
                sourceNodeId = Uuid.random(),
                sourceNodeIdStr = Uuid.random()
                    .toString(),
                targetNodeId = nodeId,
                targetNodeIdStr = nodeId.toString(),
                rsyncImage = "alpine",
                playerWarningMessage = "Server restarting",
                containerNamePrefix = "craftpanel",
                serverRow = ServerRow(
                    id = Uuid.random(), name = "test", displayName = "test",
                    description = null, nodeId = Uuid.random(), networkId = null,
                    serverType = ServerType.VANILLA, mcVersion = "1.21.4", status = "RUNNING",
                    hostPort = 25565, memoryMb = 1024, cpuShares = 0,
                    exposedExternally = false, publicSubdomain = null,
                    dnsRecordId = null, dnsRecordName = null, customHostname = null,
                    configMode = "MANAGED", stopCommand = "stop", itzgImageTag = "latest",
                    needsRecreate = false, backupSchedule = null, backupMaxCount = 0,
                    backupScheduleLastFired = null, lastPlayerCount = null,
                    lastPlayerNames = null, lastPlayerUpdate = null, lastSeenAt = null,
                    createdAt = "", updatedAt = ""
                ),
                targetNodeRow = NodeRow(
                    id = nodeId, displayName = "target-node", hostname = "target-node",
                    publicIp = "5.6.7.8", privateIp = "10.0.0.2", tokenHash = "",
                    status = "ACTIVE", health = "HEALTHY", totalRamMb = 16384, totalCpuShares = 0,
                    systemRamUsedMb = null, portRangeStart = 25565, portRangeEnd = 25600,
                    swarmActive = false, agentVersion = null, lastSeenAt = null,
                    createdAt = "", updatedAt = ""
                ),
                targetPrivateIp = "10.0.0.2"
            )
            val repos = TestRepositories()
            coord = MigrationCoordinator(
                migrationRepository = repos.migrationRepository,
                serverRepository = repos.serverRepository,
                portRepository = repos.portRepository,
                proxyBackendRepository = repos.proxyBackendRepository,
                nodeRepository = NodeRepositoryImpl(),
                gateway = TestAgentGateway(),
                dnsProvider = null,
                lifecycle = ContainerLifecycle(
                    gateway = TestAgentGateway(),
                    modService = ModService(modRepository = repos.modRepository, serverRepository = repos.serverRepository),
                    serverRepository = repos.serverRepository,
                    envVarsRepository = repos.envVarsRepository
                ),
                serverExposure = ServerExposure(NetworkRepositoryImpl(), SettingsRepositoryImpl(), repos.serverRepository),
                scope = TestScope(),
                eventFlow = MutableSharedFlow()
            )
        }

        test("returns Success and registers port when port available") {
            runTest {
                val step = AllocateRsyncPortStep()
                val result = step.execute(plan, coord)
                result.shouldBeInstanceOf<StepResult.Success>()
                plan.rsyncPort shouldBe 25565
            }
        }

        test("returns Success with next port when first port taken") {
            runTest {
                coord.portRepository.registerPort(plan.targetNodeId, 25565, "TCP", null)
                val step = AllocateRsyncPortStep()
                val result = step.execute(plan, coord)
                result.shouldBeInstanceOf<StepResult.Success>()
                plan.rsyncPort shouldBe 25566
            }
        }

        test("returns Failure when port range exhausted") {
            runTest {
                for (i in 25565..25600) {
                    coord.portRepository.registerPort(plan.targetNodeId, i, "TCP", null)
                }
                val step = AllocateRsyncPortStep()
                val result = step.execute(plan, coord)
                result.shouldBeInstanceOf<StepResult.Failure>()
                result.error shouldBe "Port allocation failed: No free ports in range 25565-25600 on node ${plan.targetNodeId}"
            }
        }

        test("allocateRsyncPort is testable with a fake coordinator and plain plan (no DB)") {
            runTest {
                val fakeCoord = object : MigrationCoordinator(
                    migrationRepository = coord.migrationRepository,
                    serverRepository = coord.serverRepository,
                    portRepository = coord.portRepository,
                    proxyBackendRepository = coord.proxyBackendRepository,
                    nodeRepository = coord.nodeRepository,
                    gateway = coord.gateway,
                    dnsProvider = null,
                    lifecycle = coord.lifecycle,
                    serverExposure = ServerExposure(NetworkRepositoryImpl(), SettingsRepositoryImpl(), coord.serverRepository),
                    scope = coord.scope,
                    eventFlow = null
                ) {
                    override fun allocateRsyncPort(plan: MigrationPlan): Int = 40000
                }

                val step = AllocateRsyncPortStep()
                val result = step.execute(plan, fakeCoord)
                result.shouldBeInstanceOf<StepResult.Success>()
                plan.rsyncPort shouldBe 40000
            }
        }
    })
