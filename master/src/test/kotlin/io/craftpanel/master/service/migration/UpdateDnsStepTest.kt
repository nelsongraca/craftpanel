package io.craftpanel.master.service.migration

import io.craftpanel.master.TestAgentGateway
import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.dns.DnsProvider
import io.craftpanel.master.service.ContainerLifecycle
import io.craftpanel.master.service.ModService
import io.craftpanel.master.service.ServerExposure
import io.craftpanel.master.service.migration.steps.UpdateDnsStep
import io.craftpanel.master.service.repo.NetworkRepositoryImpl
import io.craftpanel.master.service.repo.NodeRepositoryImpl
import io.craftpanel.master.service.repo.NodeRow
import io.craftpanel.master.service.repo.ServerRow
import io.craftpanel.master.service.repo.SettingsRepositoryImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class UpdateDnsStepTest :
    FunSpec({
        lateinit var nodeId: Uuid
        lateinit var serverId: Uuid
        lateinit var plan: MigrationPlan

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
                    dnsRecordId = "rec-1", dnsRecordName = "test.example.com", customHostname = null,
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
        }

        fun coordWith(dnsProvider: DnsProvider?, resolveDns: ServerExposure.NetworkDns?): MigrationCoordinator {
            val repos = TestRepositories()
            return object : MigrationCoordinator(
                migrationRepository = repos.migrationRepository,
                serverRepository = repos.serverRepository,
                portRepository = repos.portRepository,
                proxyBackendRepository = repos.proxyBackendRepository,
                nodeRepository = NodeRepositoryImpl(),
                gateway = TestAgentGateway(),
                dnsProvider = dnsProvider,
                lifecycle = ContainerLifecycle(
                    gateway = TestAgentGateway(),
                    modService = ModService(modRepository = repos.modRepository, serverRepository = repos.serverRepository),
                    serverRepository = repos.serverRepository,
                    envVarsRepository = repos.envVarsRepository
                ),
                serverExposure = ServerExposure(NetworkRepositoryImpl(), SettingsRepositoryImpl(), repos.serverRepository),
                scope = TestScope(),
                eventFlow = null
            ) {
                override fun resolveTargetDns(plan: MigrationPlan): ServerExposure.NetworkDns? = resolveDns
            }
        }

        test("no-op success when server has no dnsRecordId") {
            runTest {
                val noRecordPlan = plan.copy(serverRow = plan.serverRow.copy(dnsRecordId = null))
                val coord = coordWith(null, null)
                val result = UpdateDnsStep().execute(noRecordPlan, coord)
                result.shouldBeInstanceOf<StepResult.Success>()
            }
        }

        test("no-op success when dnsProvider is null") {
            runTest {
                val coord = coordWith(null, null)
                val result = UpdateDnsStep().execute(plan, coord)
                result.shouldBeInstanceOf<StepResult.Success>()
            }
        }

        test("no-op success when network DNS cannot be resolved") {
            runTest {
                val provider = object : DnsProvider {
                    override val type = "test"

                    override fun createARecord(zoneId: String, hostname: String, ip: String, ttl: Int) = "rec"

                    override fun updateARecord(zoneId: String, recordId: String, ip: String, ttl: Int) {}

                    override fun deleteARecord(zoneId: String, recordId: String) {}
                }
                val coord = coordWith(provider, null)
                val result = UpdateDnsStep().execute(plan, coord)
                result.shouldBeInstanceOf<StepResult.Success>()
            }
        }

        test("updates A record when provider and DNS resolve") {
            runTest {
                var calledZone: String? = null
                var calledRecordId: String? = null
                var calledIp: String? = null
                val provider = object : DnsProvider {
                    override val type = "test"

                    override fun createARecord(zoneId: String, hostname: String, ip: String, ttl: Int) = "rec"

                    override fun updateARecord(zoneId: String, recordId: String, ip: String, ttl: Int) {
                        calledZone = zoneId
                        calledRecordId = recordId
                        calledIp = ip
                    }

                    override fun deleteARecord(zoneId: String, recordId: String) {}
                }
                val coord = coordWith(provider, ServerExposure.NetworkDns("zone-1", "example.com"))
                val result = UpdateDnsStep().execute(plan, coord)
                result.shouldBeInstanceOf<StepResult.Success>()
                calledZone shouldBe "zone-1"
                calledRecordId shouldBe "rec-1"
                calledIp shouldBe "5.6.7.8"
            }
        }

        test("failure when provider update throws") {
            runTest {
                val provider = object : DnsProvider {
                    override val type = "test"

                    override fun createARecord(zoneId: String, hostname: String, ip: String, ttl: Int) = "rec"

                    override fun updateARecord(zoneId: String, recordId: String, ip: String, ttl: Int): Unit = throw RuntimeException("dns api down")

                    override fun deleteARecord(zoneId: String, recordId: String) {}
                }
                val coord = coordWith(provider, ServerExposure.NetworkDns("zone-1", "example.com"))
                val result = UpdateDnsStep().execute(plan, coord)
                result.shouldBeInstanceOf<StepResult.Failure>()
            }
        }
    })
