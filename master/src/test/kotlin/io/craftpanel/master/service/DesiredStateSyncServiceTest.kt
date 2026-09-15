package io.craftpanel.master.service

import io.craftpanel.master.TestAgentGateway
import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
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
                serverRepository = repos.serverRepository,
                envVarsRepository = repos.envVarsRepository,
            ),
            serverRepository = repos.serverRepository,
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
                it[Nodes.totalCpuShares] = 0
                it[Nodes.portRangeStart] = 25565
                it[Nodes.portRangeEnd] = 25600
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        fun createServer(nodeId: Uuid, desiredStatus: String?): Uuid = transaction {
            Servers.insert {
                it[Servers.nodeId] = nodeId
                it[Servers.name] = "s-${Uuid.random()}"
                it[Servers.hostPort] = 25565
                it[Servers.memoryMb] = 1024
                it[Servers.status] = "STOPPED"
                it[Servers.desiredStatus] = desiredStatus
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

        test("pushAllForNode skips servers with unset desired status") {
            runTest {
                createServer(nodeId, null)
                service().pushAllForNode(nodeId.toString())
                gateway.sent.size shouldBe 0
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