package io.craftpanel.master.service.migration

import io.craftpanel.master.*
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.service.*
import io.craftpanel.master.service.repo.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class MigrationRunnerTest :
    FunSpec({
        lateinit var nodeId: Uuid
        lateinit var targetNodeId: Uuid
        lateinit var serverId: Uuid
        lateinit var coord: MigrationCoordinator

        fun createPlan(migrationId: Uuid = Uuid.random()): MigrationPlan = MigrationPlan(
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
                createdAt = "", updatedAt = ""
            ),
            targetNodeRow = NodeRow(
                id = targetNodeId, displayName = "target", hostname = "target",
                publicIp = "5.6.7.8", privateIp = "10.0.0.2", tokenHash = "",
                status = "ACTIVE", health = "HEALTHY", totalRamMb = 16384, totalCpuShares = 0,
                systemRamUsedMb = null, portRangeStart = 25565, portRangeEnd = 25600,
                swarmActive = false, agentVersion = null, lastSeenAt = null,
                createdAt = "", updatedAt = ""
            ),
            targetPrivateIp = "10.0.0.2"
        )

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
            val repos = TestRepositories()
            coord = MigrationCoordinator(
                migrationRepository = repos.migrationRepository,
                serverRepository = repos.serverRepository,
                portRepository = repos.portRepository,
                proxyBackendRepository = repos.proxyBackendRepository,
                nodeRepository = NodeRepositoryImpl(),
                gateway = TestAgentGateway(agentEvents = MutableSharedFlow()),
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

        test("all steps succeed - status COMPLETED") {
            runTest {
                val migration = coord.migrationRepository.createMigration(serverId, nodeId, targetNodeId)
                val plan = createPlan(migration.id)

                val allSuccess = object : MigrationStep {
                    override val stepNumber = 1
                    override val description = "Always succeeds"
                    override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator) = StepResult.Success
                }

                MigrationRunner(listOf(allSuccess), plan, coord).run()
                val row = coord.migrationRepository.findMigrationById(plan.migrationId)
                row?.status shouldBe MigrationStatus.COMPLETED.name
            }
        }

        test("step fails - runner stops and remaining steps not executed") {
            runTest {
                var secondExecuted = false
                val migration = coord.migrationRepository.createMigration(serverId, nodeId, targetNodeId)
                val plan = createPlan(migration.id)

                val steps = listOf(
                    object : MigrationStep {
                        override val stepNumber = 1
                        override val description = "Fails"
                        override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator) = StepResult.Failure("Step 1 failed")
                    },
                    object : MigrationStep {
                        override val stepNumber = 2
                        override val description = "Should not run"
                        override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator): StepResult {
                            secondExecuted = true
                            return StepResult.Success
                        }
                    }
                )

                MigrationRunner(steps, plan, coord).run()
                val row = coord.migrationRepository.findMigrationById(plan.migrationId)
                row?.status shouldBe MigrationStatus.FAILED.name
                secondExecuted shouldBe false
            }
        }

        test("finally block cleans up rsync port even on failure") {
            runTest {
                val migration = coord.migrationRepository.createMigration(serverId, nodeId, targetNodeId)
                val plan = createPlan(migration.id)
                plan.rsyncPort = 25566
                coord.portRepository.registerPort(plan.targetNodeId, 25566, "TCP", null)

                val failing = object : MigrationStep {
                    override val stepNumber = 1
                    override val description = "Fails"
                    override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator) = StepResult.Failure("fail")
                }

                MigrationRunner(listOf(failing), plan, coord).run()
                val usedPorts = coord.portRepository.findUsedPortsOnNode(plan.targetNodeId)
                usedPorts shouldBe emptyList()
            }
        }

        test("runner works against a fake coordinator subclass (proves plan/coord seam is swappable)") {
            runTest {
                val migration = coord.migrationRepository.createMigration(serverId, nodeId, targetNodeId)
                val plan = createPlan(migration.id)
                var completeStepCalls = 0

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
                    override fun completeStep(stepId: Uuid, success: Boolean, error: String?) {
                        completeStepCalls++
                        super.completeStep(stepId, success, error)
                    }
                }

                val allSuccess = object : MigrationStep {
                    override val stepNumber = 1
                    override val description = "Always succeeds"
                    override suspend fun execute(plan: MigrationPlan, coord: MigrationCoordinator) = StepResult.Success
                }

                MigrationRunner(listOf(allSuccess), plan, fakeCoord).run()
                completeStepCalls shouldBe 1
            }
        }
    })
