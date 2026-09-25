package io.craftpanel.master.service

import io.craftpanel.master.TestAgentGateway
import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.service.repo.impl.SettingsRepositoryImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class DesiredStateSyncServiceTest :
    FunSpec({
        val repos = TestRepositories()
        lateinit var gateway: TestAgentGateway
        lateinit var nodeId: Uuid

        fun service() = DesiredStateSyncService(
            lifecycle = ContainerLifecycle(
                gateway = gateway,
                modService = ModService(modRepository = repos.modRepository, serverRepository = repos.serverRepository),
                serverIntent = ServerIntent(repos.serverRepository),
                envVarsRepository = repos.envVarsRepository,
                extraPortRepository = repos.extraPortRepository,
                serverHostnames = ServerHostnames(SettingsProvider(SettingsRepositoryImpl()), repos.serverRepository),
            ),
            serverRepository = repos.serverRepository,
            serverIntent = ServerIntent(repos.serverRepository)
        )

        fun createNode(): Uuid = transaction {
            Nodes.insert {
                it[Nodes.hostname] = "n-${Uuid.random()}"
                it[Nodes.displayName] = "n"
                it[Nodes.publicIp] = "1.2.3.4"
                it[Nodes.privateIp] = "10.0.0.1"
                it[Nodes.tokenHash] = Uuid.random()
                    .toString()
                    .replace("-", "")
                    .padEnd(64, '0')
                it[Nodes.status] = "ACTIVE"
                it[Nodes.totalRamMb] = 8192
                it[Nodes.totalCpuMillicores] = 0
                it[Nodes.portRangeStart] = 25565
                it[Nodes.portRangeEnd] = 25600
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        fun createServer(
            nodeId: Uuid,
            desiredStatus: String?,
            status: String = "STOPPED",
            customHostname: String? = null,
            dnsRecordName: String? = null,
            exposedExternally: Boolean = false
        ): Uuid = transaction {
            Servers.insert {
                it[Servers.nodeId] = nodeId
                it[Servers.name] = "s-${Uuid.random()}"
                it[Servers.hostPort] = 25565
                it[Servers.memoryMb] = 1024
                it[Servers.status] = status
                it[Servers.desiredStatus] = desiredStatus
                it[Servers.customHostname] = customHostname
                it[Servers.dnsRecordName] = dnsRecordName
                it[Servers.exposedExternally] = exposedExternally
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
            gateway = TestAgentGateway()
            nodeId = createNode()
        }

        test("pushAllForNode sends an envelope for each server with a desired status") {
            runTest {
                createServer(nodeId, "RUNNING")
                createServer(nodeId, "STOPPED")
                service().pushAllForNode(nodeId.toString())
                gateway.sent.size shouldBe 2
                gateway.sent.all { it.second.hasServerDesiredState() } shouldBe true
            }
        }

        test("pushAllForNode skips a server with unset intent and a STOPPED report") {
            runTest {
                createServer(nodeId, null, status = "STOPPED")
                service().pushAllForNode(nodeId.toString())
                gateway.sent.size shouldBe 0
            }
        }

        test("pushAllForNode re-derives RUNNING for unset intent with a running report and persists it") {
            runTest {
                val id = createServer(nodeId, null, status = "HEALTHY")
                service().pushAllForNode(nodeId.toString())
                gateway.sent.size shouldBe 1
                gateway.sent.single().second.serverDesiredState.desired shouldBe
                    io.craftpanel.proto.ServerDesiredState.Desired.RUNNING
                repos.serverRepository.findById(id)?.desiredStatus shouldBe "RUNNING"
            }
        }

        test("pushAllForNode re-derives RUNNING for a crash-looped server with unset intent") {
            runTest {
                createServer(nodeId, null, status = "CRASH_LOOPED")
                service().pushAllForNode(nodeId.toString())
                gateway.sent.size shouldBe 1
                gateway.sent.single().second.serverDesiredState.desired shouldBe
                    io.craftpanel.proto.ServerDesiredState.Desired.RUNNING
            }
        }

        test("pushAllForNode only targets the requested node") {
            runTest {
                val otherNode = createNode()
                createServer(nodeId, "RUNNING")
                createServer(otherNode, "RUNNING")
                service().pushAllForNode(nodeId.toString())
                gateway.sent.size shouldBe 1
                gateway.sent[0].first shouldBe nodeId.toString()
            }
        }

        test("pushAllForNode carries the mc-router routing label on reconnect") {
            runTest {
                createServer(nodeId, "RUNNING", customHostname = "play.example.com")
                service().pushAllForNode(nodeId.toString())
                gateway.sent.single().second.serverDesiredState.spec.publicHostname shouldBe "play.example.com"
            }
        }

        test("pushAllForNode sends no routing label for an unexposed server") {
            runTest {
                createServer(nodeId, "RUNNING")
                service().pushAllForNode(nodeId.toString())
                gateway.sent.single().second.serverDesiredState.spec.publicHostname shouldBe ""
            }
        }

        test("pushAllOnBoot pushes across all nodes") {
            runTest {
                val otherNode = createNode()
                createServer(nodeId, "RUNNING")
                createServer(otherNode, "STOPPED")
                service().pushAllOnBoot()
                gateway.sent.size shouldBe 2
            }
        }

        test("pushAllForNode tolerates a disconnected agent") {
            runTest {
                gateway = TestAgentGateway(sendResult = false)
                createServer(nodeId, "RUNNING")
                service().pushAllForNode(nodeId.toString())
                gateway.sent.size shouldBe 1
            }
        }
    })
