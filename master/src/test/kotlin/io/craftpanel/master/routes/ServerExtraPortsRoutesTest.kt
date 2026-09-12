package io.craftpanel.master.routes

import io.craftpanel.master.*
import io.craftpanel.master.auth.*
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.routes.dto.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ServerExtraPortsRoutesTest :
    FunSpec({
        val jwtConfig = JwtConfig(
            secret = "test-secret-that-is-at-least-32-characters!!",
            issuer = "craftpanel-test",
            audience = "craftpanel-test",
            expirySeconds = 900
        )
        val jwtManager = JwtManager(jwtConfig)

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        val repos = TestRepositories()

        fun Route.configurePortsTest() {
            serverExtraPortsRoutes(repos.serverRepository, repos.extraPortRepository)
        }

        fun createUser(email: String = "admin@example.com"): Uuid = transaction {
            Users.insert {
                it[Users.username] = email.substringBefore("@")
                it[Users.email] = email
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

        fun tokenFor(userId: Uuid): String = jwtManager.generate(TokenClaims(userId = userId, name = "Admin", email = "admin@example.com", groups = emptyList()))

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

        test("GET /api/servers/{id}/ports returns primary and extra ports") {
            testApplication {
                testApp { _ -> configurePortsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val token = tokenFor(userId)
                val nodeId = createNode()
                val serverId = createServer(nodeId)

                repos.extraPortRepository.createExtraPort(
                    serverId = serverId,
                    nodeId = nodeId,
                    name = "Dynmap",
                    containerPort = 8123,
                    hostPort = 8123,
                    protocol = "TCP"
                )

                val response = client.get("/api/servers/$serverId/ports") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

                response.status shouldBe HttpStatusCode.OK
                val body = response.body<ServerPortsResponse>()
                body.primaryPort.hostPort shouldBe 25565
                body.extraPorts.size shouldBe 1
                body.extraPorts[0].name shouldBe "Dynmap"
                body.extraPorts[0].containerPort shouldBe 8123
                body.extraPorts[0].hostPort shouldBe 8123
                body.extraPorts[0].protocol shouldBe "TCP"
            }
        }

        test("POST /api/servers/{id}/ports creates extra port") {
            testApplication {
                testApp { _ -> configurePortsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val token = tokenFor(userId)
                val nodeId = createNode()
                val serverId = createServer(nodeId)

                val response = client.post("/api/servers/$serverId/ports") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(CreateServerExtraPortRequest(name = "Geyser", containerPort = 19132, protocol = "UDP"))
                }

                response.status shouldBe HttpStatusCode.Created
                val body = response.body<ServerExtraPortResponse>()
                body.name shouldBe "Geyser"
                body.containerPort shouldBe 19132
                body.protocol shouldBe "UDP"
                body.hostPort shouldNotBe 0
            }
        }

        test("DELETE /api/servers/{id}/ports/{portId} deletes extra port") {
            testApplication {
                testApp { _ -> configurePortsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val token = tokenFor(userId)
                val nodeId = createNode()
                val serverId = createServer(nodeId)

                val created = repos.extraPortRepository.createExtraPort(
                    serverId = serverId,
                    nodeId = nodeId,
                    name = "Votifier",
                    containerPort = 8192,
                    hostPort = 8192,
                    protocol = "TCP"
                )

                val response = client.delete("/api/servers/$serverId/ports/${created.id}") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

                response.status shouldBe HttpStatusCode.NoContent

                val listResponse = repos.extraPortRepository.findByServerId(serverId)
                listResponse.size shouldBe 0
            }
        }
    })
