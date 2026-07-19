package io.craftpanel.master.service.migration

import io.craftpanel.master.*
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.service.*
import io.craftpanel.master.service.migration.steps.AssignTargetPortStep
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class AssignTargetPortStepTest :
    FunSpec({
        lateinit var nodeId: Uuid
        lateinit var targetNodeId: Uuid
        lateinit var serverId: Uuid
        lateinit var plan: MigrationPlan
        lateinit var coord: MigrationCoordinator

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
                    it[Nodes.portRangeEnd] = 25567
                }[Nodes.id].let { Uuid.parse(it.toString()) }
            }
            plan = MigrationPlan(
                migrationId = Uuid.random(),
                migrationIdStr = "mig-1",
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
                    id = targetNodeId, displayName = "target-node", hostname = "target-node",
                    publicIp = "5.6.7.8", privateIp = "10.0.0.2", tokenHash = "",
                    status = "ACTIVE", health = "HEALTHY", totalRamMb = 16384, totalCpuShares = 0,
                    systemRamUsedMb = null, portRangeStart = 25565, portRangeEnd = 25567,
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
                eventFlow = null
            )
        }

        test("reuses existing host port when free on target node") {
            runTest {
                val result = AssignTargetPortStep().execute(plan, coord)
                result.shouldBeInstanceOf<StepResult.Success>()
                plan.assignedPort shouldBe 25565
                plan.freshServerRow?.hostPort shouldBe 25565
            }
        }

        test("assigns next free port when existing port already used on target node") {
            runTest {
                transaction {
                    PortRegistry.insert {
                        it[PortRegistry.nodeId] = targetNodeId
                        it[PortRegistry.port] = 25565
                        it[PortRegistry.protocol] = "TCP"
                        it[PortRegistry.serverId] = null
                    }
                }
                val result = AssignTargetPortStep().execute(plan, coord)
                result.shouldBeInstanceOf<StepResult.Success>()
                plan.assignedPort shouldBe 25566
            }
        }

        test("failure when target node has no free ports") {
            runTest {
                transaction {
                    (25565..25567).forEach { p ->
                        PortRegistry.insert {
                            it[PortRegistry.nodeId] = targetNodeId
                            it[PortRegistry.port] = p
                            it[PortRegistry.protocol] = "TCP"
                            it[PortRegistry.serverId] = null
                        }
                    }
                }
                val result = AssignTargetPortStep().execute(plan, coord)
                result.shouldBeInstanceOf<StepResult.Failure>()
            }
        }
    })
