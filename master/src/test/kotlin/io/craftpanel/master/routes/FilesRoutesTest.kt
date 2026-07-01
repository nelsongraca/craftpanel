package io.craftpanel.master.routes

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.TokenClaims
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.grpc.BulkDataServiceImpl
import io.craftpanel.master.grpc.ControlServiceImpl
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.jsonClient
import io.craftpanel.master.service.NodeStateReconciler
import io.craftpanel.master.service.repo.NodeRepositoryImpl
import io.craftpanel.master.service.repo.ServerRepositoryImpl
import io.craftpanel.master.testApp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.routing.Route
import io.ktor.server.testing.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class FilesRoutesTest : FunSpec({
    val jwtConfig = JwtConfig(
        secret = "test-secret-that-is-at-least-32-characters!!",
        issuer = "craftpanel-test",
        audience = "craftpanel-test",
        expirySeconds = 900,
    )
    val jwtManager = JwtManager(jwtConfig)
    val noopControlSvc = ControlServiceImpl(NodeConfig("test-token", 50052), NodeStateReconciler(ServerRepositoryImpl(), NodeRepositoryImpl()))
    val noopProxy = DataServiceProxy(noopControlSvc, BulkDataServiceImpl(noopControlSvc))

    beforeTest {
        TestDatabase.initIfNeeded()
        TestDatabase.reset()
    }

    fun Route.configureFilesTest() {
        filesRoutes(noopProxy)
    }

    fun createUser(): Uuid = transaction {
        Users.insert {
            it[Users.username] = "admin"
            it[Users.email] = "admin@example.com"
            it[Users.passwordHash] = Argon2Hasher.hash("hunter2")
            it[Users.isActive] = true
        }[Users.id].let { Uuid.parse(it.toString()) }
    }

    fun assignGlobalGroup(userId: Uuid, groupName: String) = transaction {
        val groupId = Groups.selectAll()
            .where { Groups.name eq groupName }
            .first()[Groups.id]
        UserGroupAssignments.insert {
            it[UserGroupAssignments.userId] = userId
            it[UserGroupAssignments.groupId] = groupId
            it[UserGroupAssignments.scopeType] = "GLOBAL"
        }
    }

    fun tokenFor(userId: Uuid): String =
        jwtManager.generate(TokenClaims(userId = userId, name = "admin", email = "admin@example.com", groups = emptyList()))

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

    // ── Authorization ─────────────────────────────────────────────────────────

    test("list files returns 401 without token") {
        testApplication {
            testApp { _ -> configureFilesTest() }
            val client = jsonClient()
            val nodeId = createNode()
            val serverId = createServer(nodeId)
            val res = client.get("/api/servers/$serverId/files")
            res.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("list files returns 403 without server_files permission") {
        testApplication {
            testApp { _ -> configureFilesTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Viewer")
            val token = tokenFor(userId)
            val nodeId = createNode()
            val serverId = createServer(nodeId)
            val res = client.get("/api/servers/$serverId/files") { header("Authorization", "Bearer $token") }
            res.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("list files returns 404 for unknown server") {
        testApplication {
            testApp { _ -> configureFilesTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val token = tokenFor(userId)
            val res = client.get("/api/servers/${Uuid.random()}/files") { header("Authorization", "Bearer $token") }
            res.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("read file returns 401 without token") {
        testApplication {
            testApp { _ -> configureFilesTest() }
            val client = jsonClient()
            val nodeId = createNode()
            val serverId = createServer(nodeId)
            val res = client.get("/api/servers/$serverId/files/content?path=/test.txt")
            res.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("read file returns 403 without server_files permission") {
        testApplication {
            testApp { _ -> configureFilesTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Viewer")
            val token = tokenFor(userId)
            val nodeId = createNode()
            val serverId = createServer(nodeId)
            val res = client.get("/api/servers/$serverId/files/content?path=/test.txt") { header("Authorization", "Bearer $token") }
            res.status shouldBe HttpStatusCode.Forbidden
        }
    }
})
