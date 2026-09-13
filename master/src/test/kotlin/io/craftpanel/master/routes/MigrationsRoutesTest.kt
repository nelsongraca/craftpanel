package io.craftpanel.master.routes

import io.craftpanel.master.*
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.TokenClaims
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.service.*
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.call.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class MigrationsRoutesTest :
    FunSpec({
        val jwtConfig = JwtConfig(
            secret = "test-secret-that-is-at-least-32-characters!!",
            issuer = "craftpanel-test",
            audience = "craftpanel-test",
            expirySeconds = 900
        )
        val jwtManager = JwtManager(jwtConfig)
        val noopGateway = TestAgentGateway()
        val testScope = TestScope()

        val repos = TestRepositories()

        fun buildMigrationService(): MigrationService = MigrationService(
            migrationRepository = repos.migrationRepository,
            serverRepository = repos.serverRepository,
            portRepository = repos.portRepository,
            proxyBackendRepository = repos.proxyBackendRepository,
            nodeRepository = NodeRepositoryImpl(),
            gateway = noopGateway,
            dnsProvider = null,
            scope = testScope,
            lifecycle = ContainerLifecycle(
                gateway = TestAgentGateway(),
                modService = ModService(modRepository = repos.modRepository, serverRepository = repos.serverRepository),
                serverRepository = repos.serverRepository,
                envVarsRepository = repos.envVarsRepository
            ),
            serverExposure = ServerExposure(
                settingsRepository = SettingsRepositoryImpl(),
                serverRepository = repos.serverRepository
            )
        )

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        fun Route.configureMigrationsTest(svc: MigrationService) {
            migrationsRoutes(WsTicketService(), svc)
        }

        fun createUserJwt(groupName: String): Pair<Uuid, String> {
            val userId = transaction {
                Users.insert {
                    it[Users.username] = "user-${Uuid.random()}"
                    it[Users.email] = "user-${Uuid.random()}@test.com"
                    it[Users.passwordHash] = "hash"
                    it[Users.isActive] = true
                }[Users.id].let { Uuid.parse(it.toString()) }
            }
            transaction {
                val groupId = Groups.selectAll()
                    .where { Groups.name eq groupName }
                    .first()[Groups.id]
                UserGroupAssignments.insert {
                    it[UserGroupAssignments.userId] = userId
                    it[UserGroupAssignments.groupId] = groupId
                    it[UserGroupAssignments.scopeType] = "GLOBAL"
                }
            }
            val jwt = jwtManager.generate(TokenClaims(userId = userId, name = "Admin", email = "admin@test.com", groups = listOf(groupName)))
            return userId to jwt
        }

        fun createSuperAdminJwt(): String = createUserJwt("Super Admin").second

        fun insertNode(status: String = "ACTIVE"): Pair<Uuid, Uuid> {
            val nodeId = transaction {
                Nodes.insert {
                    it[Nodes.displayName] = "Test Node"
                    it[Nodes.hostname] = "node1.test"
                    it[Nodes.publicIp] = "1.2.3.4"
                    it[Nodes.privateIp] = "10.0.0.1"
                    it[Nodes.tokenHash] = Uuid.random()
                        .toString()
                    it[Nodes.status] = status
                }[Nodes.id]
            }
            return (nodeId.value) to nodeId.value
        }

        fun insertServer(nodeId: Uuid): Pair<Uuid, Uuid> {
            val serverId = transaction {
                Servers.insert {
                    it[Servers.name] = "test-server-${Uuid.random()}"
                    it[Servers.displayName] = "Test Server"
                    it[Servers.nodeId] = nodeId
                    it[Servers.hostPort] = 25565
                    it[Servers.memoryMb] = 1024
                    it[Servers.status] = "HEALTHY"
                }[Servers.id]
            }
            return Uuid.parse(serverId.toString()) to serverId.value
        }

        fun insertMigration(serverId: Uuid, sourceNodeId: Uuid, targetNodeId: Uuid, status: String = "PENDING"): Uuid = transaction {
            ServerMigrations.insert {
                it[ServerMigrations.serverId] = EntityID(serverId, Servers)
                it[ServerMigrations.sourceNodeId] = EntityID(sourceNodeId, Nodes)
                it[ServerMigrations.targetNodeId] = EntityID(targetNodeId, Nodes)
                it[ServerMigrations.status] = status
            }[ServerMigrations.id].let { Uuid.parse(it.toString()) }
        }

        test("list migrations returns empty for new server") {
            testApplication {
                val (_, sourceNodeKId) = insertNode()
                val (serverJavaId, _) = insertServer(sourceNodeKId)
                val token = createSuperAdminJwt()
                testApp(extraPlugins = { install(WebSockets) }) { _ -> configureMigrationsTest(buildMigrationService()) }
                val client = jsonClient()

                val response = client.get("/api/servers/$serverJavaId/migrations") {
                    bearerAuth(token)
                }
                response.status shouldBe HttpStatusCode.OK
                val body = response.body<JsonObject>()
                body["migrations"]!!.jsonArray.size shouldBe 0
            }
        }

        test("start migration returns 404 when server not found") {
            testApplication {
                val token = createSuperAdminJwt()
                testApp(extraPlugins = { install(WebSockets) }) { _ -> configureMigrationsTest(buildMigrationService()) }
                val client = jsonClient()

                val response = client.post("/api/servers/${Uuid.random()}/migrations") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"target_node_id":"${Uuid.random()}"}""")
                }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("start migration returns 409 when source and target are same node") {
            testApplication {
                val (nodeJavaId, nodeKId) = insertNode()
                val (serverJavaId, _) = insertServer(nodeKId)
                val token = createSuperAdminJwt()
                testApp(extraPlugins = { install(WebSockets) }) { _ -> configureMigrationsTest(buildMigrationService()) }
                val client = jsonClient()

                val response = client.post("/api/servers/$serverJavaId/migrations") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"target_node_id":"$nodeJavaId"}""")
                }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("start migration returns 404 when target node not found") {
            testApplication {
                val (_, sourceKId) = insertNode()
                val (serverJavaId, _) = insertServer(sourceKId)
                val token = createSuperAdminJwt()
                testApp(extraPlugins = { install(WebSockets) }) { _ -> configureMigrationsTest(buildMigrationService()) }
                val client = jsonClient()

                val response = client.post("/api/servers/$serverJavaId/migrations") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"target_node_id":"${Uuid.random()}"}""")
                }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("start migration returns 409 when target node is not ACTIVE") {
            testApplication {
                val (_, sourceKId) = insertNode()
                val (targetJavaId, _) = insertNode(status = "PENDING")
                val (serverJavaId, _) = insertServer(sourceKId)
                val token = createSuperAdminJwt()
                testApp(extraPlugins = { install(WebSockets) }) { _ -> configureMigrationsTest(buildMigrationService()) }
                val client = jsonClient()

                val response = client.post("/api/servers/$serverJavaId/migrations") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"target_node_id":"$targetJavaId"}""")
                }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("start migration returns 202 and creates pending migration") {
            testApplication {
                val (_, sourceKId) = insertNode()
                val (targetJavaId, targetKId) = insertNode()
                transaction {
                    PortRegistry.insert {
                        it[PortRegistry.nodeId] = targetKId
                        it[PortRegistry.port] = 25570
                        it[PortRegistry.protocol] = "TCP"
                        it[PortRegistry.serverId] = null
                    }
                }
                val (serverJavaId, _) = insertServer(sourceKId)
                val token = createSuperAdminJwt()
                testApp(extraPlugins = { install(WebSockets) }) { _ -> configureMigrationsTest(buildMigrationService()) }
                val client = jsonClient()

                val response = client.post("/api/servers/$serverJavaId/migrations") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"target_node_id":"$targetJavaId"}""")
                }
                response.status shouldBe HttpStatusCode.Accepted
                val body = response.body<JsonObject>()
                body["id"] shouldNotBe null
                body["status"]!!.jsonPrimitive.content shouldBe "PENDING"
                body["server_id"]!!.jsonPrimitive.content shouldBe serverJavaId.toString()
            }
        }

        test("get migration returns 404 for unknown id") {
            testApplication {
                val token = createSuperAdminJwt()
                testApp(extraPlugins = { install(WebSockets) }) { _ -> configureMigrationsTest(buildMigrationService()) }
                val client = jsonClient()

                val response = client.get("/api/migrations/${Uuid.random()}") {
                    bearerAuth(token)
                }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("get migration returns 403 for user without server.migrate") {
            testApplication {
                val (_, sourceKId) = insertNode()
                val (_, targetKId) = insertNode()
                val (serverJavaId, _) = insertServer(sourceKId)
                val migrationId = insertMigration(serverJavaId, sourceKId, targetKId)
                val (_, token) = createUserJwt("Viewer")
                testApp(extraPlugins = { install(WebSockets) }) { _ -> configureMigrationsTest(buildMigrationService()) }
                val client = jsonClient()

                val response = client.get("/api/migrations/$migrationId") {
                    bearerAuth(token)
                }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("get migration returns 200 for super admin with existing migration") {
            testApplication {
                val (_, sourceKId) = insertNode()
                val (_, targetKId) = insertNode()
                val (serverJavaId, _) = insertServer(sourceKId)
                val migrationId = insertMigration(serverJavaId, sourceKId, targetKId)
                val token = createSuperAdminJwt()
                testApp(extraPlugins = { install(WebSockets) }) { _ -> configureMigrationsTest(buildMigrationService()) }
                val client = jsonClient()

                val response = client.get("/api/migrations/$migrationId") {
                    bearerAuth(token)
                }
                response.status shouldBe HttpStatusCode.OK
                val body = response.body<JsonObject>()
                body["id"]!!.jsonPrimitive.content shouldBe migrationId.toString()
                body["server_id"]!!.jsonPrimitive.content shouldBe serverJavaId.toString()
            }
        }

        test("migration events websocket closes with 1008 when ticket is missing") {
            testApplication {
                val (_, sourceKId) = insertNode()
                val (_, targetKId) = insertNode()
                val (serverJavaId, _) = insertServer(sourceKId)
                val migrationId = insertMigration(serverJavaId, sourceKId, targetKId)
                testApp(extraPlugins = { install(WebSockets) }) { _ -> configureMigrationsTest(buildMigrationService()) }
                val client = jsonClient()

                client.webSocket("/api/migrations/$migrationId/events") {
                    val reason = closeReason
                    reason.await()?.code shouldBe CloseReason.Codes.VIOLATED_POLICY.code
                }
            }
        }

        test("migration events websocket closes with 1008 for user without server.migrate") {
            testApplication {
                val (_, sourceKId) = insertNode()
                val (_, targetKId) = insertNode()
                val (serverJavaId, _) = insertServer(sourceKId)
                val migrationId = insertMigration(serverJavaId, sourceKId, targetKId)
                val (viewerId, _) = createUserJwt("Viewer")
                val wsTicketService = WsTicketService()
                val rawTicket = wsTicketService.issue(viewerId).first
                testApp(extraPlugins = { install(WebSockets) }) { _ -> configureMigrationsTest(buildMigrationService()) }
                val client = jsonClient()

                client.webSocket("/api/migrations/$migrationId/events?ticket=$rawTicket") {
                    closeReason.await()?.code shouldBe CloseReason.Codes.VIOLATED_POLICY.code
                }
            }
        }
    })
