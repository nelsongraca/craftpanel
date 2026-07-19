package io.craftpanel.master.service.migration

import io.craftpanel.master.*
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.MigrationStatus
import io.craftpanel.master.domain.MigrationStepStatus
import io.craftpanel.master.service.*
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class MigrationCoordinatorTest :
    FunSpec({
        lateinit var nodeId: Uuid
        lateinit var serverId: Uuid
        lateinit var targetNodeId: Uuid
        lateinit var plan: MigrationPlan
        lateinit var coord: MigrationCoordinator

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
            plan = MigrationPlan(
                migrationId = Uuid.random(),
                migrationIdStr = Uuid.random()
                    .toString(),
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

        test("startStep creates step and returns step id") {
            runTest {
                val migration = coord.migrationRepository.createMigration(plan.serverId, plan.sourceNodeId, plan.targetNodeId)
                val p = plan.copy(migrationId = migration.id, migrationIdStr = migration.id.toString())
                val stepId = coord.startStep(p, 1, "Test step")
                stepId.shouldNotBeNull()
                val steps = coord.migrationRepository.listMigrationSteps(p.migrationId)
                steps.size shouldBe 1
                steps[0].stepNumber shouldBe 1
                steps[0].description shouldBe "Test step"
                steps[0].status shouldBe MigrationStepStatus.RUNNING.name
            }
        }

        test("completeStep marks step success") {
            runTest {
                val migration = coord.migrationRepository.createMigration(plan.serverId, plan.sourceNodeId, plan.targetNodeId)
                val p = plan.copy(migrationId = migration.id, migrationIdStr = migration.id.toString())
                val stepId = coord.startStep(p, 1, "Test step")
                coord.completeStep(stepId, true)
                val step = coord.migrationRepository.listMigrationSteps(p.migrationId)
                    .first()
                step.status shouldBe MigrationStepStatus.SUCCESS.name
            }
        }

        test("completeStep marks step failure with error") {
            runTest {
                val migration = coord.migrationRepository.createMigration(plan.serverId, plan.sourceNodeId, plan.targetNodeId)
                val p = plan.copy(migrationId = migration.id, migrationIdStr = migration.id.toString())
                val stepId = coord.startStep(p, 1, "Test step")
                coord.completeStep(stepId, false, "Something went wrong")
                val step = coord.migrationRepository.listMigrationSteps(p.migrationId)
                    .first()
                step.status shouldBe MigrationStepStatus.FAILED.name
                step.errorMessage shouldBe "Something went wrong"
            }
        }

        test("failMigration sets FAILED status") {
            runTest {
                val migration = coord.migrationRepository.createMigration(plan.serverId, plan.sourceNodeId, plan.targetNodeId)
                val p = plan.copy(migrationId = migration.id, migrationIdStr = migration.id.toString())
                coord.failMigration(p, "Test error")
                val row = coord.migrationRepository.findMigrationById(p.migrationId)
                row.shouldNotBeNull()
                row.status shouldBe MigrationStatus.FAILED.name
            }
        }

        test("restartSource does nothing when sourceStopped is false") {
            runTest {
                plan.sourceStopped = false
                coord.restartSource(plan)
            }
        }

        test("allocateRsyncPort allocates first free port") {
            runTest {
                val port = coord.allocateRsyncPort(plan)
                port shouldBe 25565
            }
        }

        test("allocateRsyncPort throws when port range exhausted") {
            runTest {
                for (i in 25565..25600) {
                    coord.portRepository.registerPort(plan.targetNodeId, i, "TCP", null)
                }
                shouldThrow<PortExhaustedException> {
                    coord.allocateRsyncPort(plan)
                }
            }
        }

        test("updateProxyBackendsAfterMigration does nothing when no proxies front the server") {
            runTest {
                val gateway = coord.gateway as TestAgentGateway
                coord.updateProxyBackendsAfterMigration(plan.serverId, plan.targetNodeRow.privateIp, 25565)
                gateway.sent shouldBe emptyList()
            }
        }

        test("updateProxyBackendsAfterMigration restarts proxies fronting the server") {
            runTest {
                val proxyServerId = transaction {
                    Servers.insert {
                        it[Servers.nodeId] = nodeId
                        it[Servers.name] = "proxy-server"
                        it[Servers.displayName] = "proxy-server"
                        it[Servers.serverType] = "PROXY"
                        it[Servers.mcVersion] = "1.21.4"
                        it[Servers.itzgImageTag] = "latest"
                        it[Servers.hostPort] = 25577
                        it[Servers.memoryMb] = 512
                        it[Servers.cpuShares] = 0
                        it[Servers.status] = "RUNNING"
                    }[Servers.id].let { id -> Uuid.parse(id.toString()) }
                }
                coord.proxyBackendRepository.replaceProxyBackends(
                    proxyServerId,
                    listOf(ProxyBackendInput(plan.serverId, "backend", 0))
                )

                val gateway = coord.gateway as TestAgentGateway
                coord.updateProxyBackendsAfterMigration(plan.serverId, plan.targetNodeRow.privateIp, 25565)

                gateway.sent.size shouldBe 1
                gateway.sent[0].first shouldBe nodeId.toString()
                gateway.sent[0].second.restartContainer.serverId shouldBe proxyServerId.toString()
            }
        }

        test("resolveTargetDns returns null when server has no network") {
            runTest {
                coord.resolveTargetDns(plan) shouldBe null
            }
        }
    })
