package io.craftpanel.master.grpc.handlers

import io.craftpanel.master.TestAgentGateway
import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.createTestControlServiceImpl
import io.craftpanel.master.database.schema.Backups
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.service.NodeStateReconciler
import io.craftpanel.master.service.repo.NodeRepositoryImpl
import io.craftpanel.proto.agentMessage
import io.craftpanel.proto.nodeStateSnapshot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class NodeStateHandlerTest :
    FunSpec({

        val repos = TestRepositories()
        val gateway = TestAgentGateway()
        val agentEvents: MutableSharedFlow<AgentEvent> = MutableSharedFlow(extraBufferCapacity = 1024)
        val reconciler = NodeStateReconciler(
            serverRepository = repos.serverRepository,
            nodeRepository = NodeRepositoryImpl(),
            migrationRepository = repos.migrationRepository,
            backupRepository = repos.backupRepository
        )
        val service = createTestControlServiceImpl(
            nodeStateReconciler = reconciler,
            agentGateway = gateway,
            repos = repos
        )

        fun createNode(): Uuid = transaction {
            Nodes.insert {
                it[Nodes.hostname] = "node-1"
                it[Nodes.displayName] = "node-1"
                it[Nodes.publicIp] = "1.2.3.4"
                it[Nodes.privateIp] = "10.0.0.1"
                it[Nodes.tokenHash] = "a".repeat(64)
                it[Nodes.status] = "ACTIVE"
                it[Nodes.totalRamMb] = 8192
                it[Nodes.totalCpuShares] = 1024
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        fun createServer(nodeId: Uuid, name: String = "survival-world"): Uuid = transaction {
            Servers.insert {
                it[Servers.name] = name
                it[Servers.displayName] = name
                it[Servers.nodeId] = nodeId
                it[Servers.serverType] = "VANILLA"
                it[Servers.mcVersion] = "LATEST"
                it[Servers.memoryMb] = 1024
                it[Servers.hostPort] = 25565
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
            gateway.sent.clear()
        }

        test("rebuild symlinks command includes server and completed backup") {
            val nodeId = createNode()
            val serverId = createServer(nodeId, "survival-world")
            transaction {
                Backups.insert {
                    it[Backups.serverId] = serverId
                    it[Backups.nodeId] = nodeId
                    it[Backups.trigger] = "MANUAL"
                    it[Backups.status] = "COMPLETED"
                    it[Backups.filePath] = "/data/backups/bk.tar.gz"
                }
            }

            val command = service.buildRebuildSymlinksCommand(nodeId)

            val rebuild = command.rebuildSymlinks
            rebuild.serversList.size shouldBe 1
            rebuild.serversList[0].serverId shouldBe serverId.toString()
            rebuild.serversList[0].serverName shouldBe "survival-world"
            rebuild.backupsList.size shouldBe 1
            rebuild.backupsList[0].backupId.isNotEmpty() shouldBe true
            rebuild.backupsList[0].serverId shouldBe serverId.toString()
            rebuild.backupsList[0].serverName shouldBe "survival-world"
            rebuild.backupsList[0].createdAtFormatted.isNotEmpty() shouldBe true
            rebuild.backupsList[0].filePath shouldBe "/data/backups/bk.tar.gz"
        }
    })
