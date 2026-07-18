package io.craftpanel.master.routes

import io.craftpanel.master.*
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.grpc.BulkDataServiceImpl
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.service.NodeStateReconciler
import io.craftpanel.master.service.SystemService
import io.craftpanel.master.service.repo.NodeRepositoryImpl
import io.craftpanel.master.service.repo.SettingsRepositoryImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ConsoleRoutesTest :
    FunSpec({
        val repos = TestRepositories()
        val reconciler = NodeStateReconciler(
            serverRepository = repos.serverRepository,
            nodeRepository = NodeRepositoryImpl(),
            migrationRepository = repos.migrationRepository,
            backupRepository = repos.backupRepository
        )
        val noopControlSvc = createTestControlServiceImpl(NodeConfig("test-token", 50052), reconciler)
        val noopProxy = DataServiceProxy(noopControlSvc, BulkDataServiceImpl(noopControlSvc), repos.serverRepository)
        val consoleRoutes = ConsoleRoutes(WsTicketService(), noopProxy, PermissionResolver, SystemService(settingsRepository = SettingsRepositoryImpl()))

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

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

        fun createServer(nodeId: Uuid): Uuid = transaction {
            Servers.insert {
                it[Servers.name] = "test-server"
                it[Servers.displayName] = "Test Server"
                it[Servers.nodeId] = nodeId
                it[Servers.serverType] = "VANILLA"
                it[Servers.mcVersion] = "LATEST"
                it[Servers.memoryMb] = 1024
                it[Servers.hostPort] = 25565
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }

        test("lookupServer returns null for a malformed id") {
            consoleRoutes.lookupServer("not-a-uuid")
                .shouldBeNull()
        }

        test("lookupServer returns null when server does not exist") {
            consoleRoutes.lookupServer(
                Uuid.random()
                    .toString()
            )
                .shouldBeNull()
        }

        test("lookupServer returns the server info when the server exists") {
            val nodeId = createNode()
            val serverId = createServer(nodeId)

            val result = consoleRoutes.lookupServer(serverId.toString())

            result shouldBe ServerInfo(serverId = serverId, networkId = null)
        }
    })
