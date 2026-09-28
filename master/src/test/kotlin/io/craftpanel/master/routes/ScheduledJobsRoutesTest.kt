package io.craftpanel.master.routes

import io.craftpanel.master.*
import io.craftpanel.master.auth.*
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.service.ScheduledJobService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ScheduledJobsRoutesTest :
    FunSpec({
        val jwtConfig = JwtConfig(
            secret = "test-secret-that-is-at-least-32-characters!!",
            issuer = "craftpanel-test",
            audience = "craftpanel-test",
            expirySeconds = 900
        )
        val jwtManager = JwtManager(jwtConfig)
        val repos = TestRepositories()

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        fun Route.configureJobsTest() {
            scheduledJobsRoutes(ScheduledJobService(repos.serverRepository, repos.serverJobRepository))
        }

        fun createUser(email: String = "admin@example.com"): Uuid = transaction {
            Users.insert {
                it[Users.username] = email.substringBefore("@")
                it[Users.email] = email
                it[Users.passwordHash] = Argon2Hasher.hash("hunter2")
                it[Users.isActive] = true
            }[Users.id].let { Uuid.parse(it.toString()) }
        }

        fun assignGlobalPermissions(userId: Uuid, vararg nodes: String) = transaction {
            val groupId = Groups.insert {
                it[Groups.name] = "g-${Uuid.random()}"
                it[Groups.isSystem] = false
            }[Groups.id]
            for (node in nodes) {
                GroupPermissions.insert {
                    it[GroupPermissions.groupId] = groupId
                    it[GroupPermissions.permission] = node
                }
            }
            UserGroupAssignments.insert {
                it[UserGroupAssignments.userId] = userId
                it[UserGroupAssignments.groupId] = groupId
                it[UserGroupAssignments.scopeType] = "GLOBAL"
            }
        }

        fun tokenFor(userId: Uuid): String = jwtManager.generate(TokenClaims(userId = userId, name = "admin", email = "admin@example.com", groups = emptyList()))

        fun createNode(): Uuid = transaction {
            Nodes.insert {
                it[Nodes.hostname] = "node-1"
                it[Nodes.displayName] = "node-1"
                it[Nodes.publicIp] = "1.2.3.4"
                it[Nodes.privateIp] = "10.0.0.1"
                it[Nodes.tokenHash] = "a".repeat(64)
                it[Nodes.status] = "ACTIVE"
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

        test("list jobs returns an empty list") {
            testApplication {
                testApp { _ -> configureJobsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalPermissions(userId, "server.cron")
                val token = tokenFor(userId)
                val serverId = createServer(createNode())

                val res = client.get("/api/servers/$serverId/jobs") { header("Authorization", "Bearer $token") }

                res.status shouldBe HttpStatusCode.OK
                res.body<JsonArray>().size shouldBe 0
            }
        }

        test("create START job with server.cron and server.start succeeds") {
            testApplication {
                testApp { _ -> configureJobsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalPermissions(userId, "server.cron", "server.start")
                val token = tokenFor(userId)
                val serverId = createServer(createNode())

                val res = client.post("/api/servers/$serverId/jobs") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"START","cron_expression":"0 3 * * *"}""")
                }

                res.status shouldBe HttpStatusCode.Created
                res.body<JsonObject>()["type"]!!.jsonPrimitive.content shouldBe "START"
            }
        }

        test("create requires server.cron") {
            testApplication {
                testApp { _ -> configureJobsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalPermissions(userId, "server.view")
                val token = tokenFor(userId)
                val serverId = createServer(createNode())

                val res = client.post("/api/servers/$serverId/jobs") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"START","cron_expression":"0 3 * * *"}""")
                }

                res.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("create RCON_COMMAND without server.console is forbidden") {
            testApplication {
                testApp { _ -> configureJobsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalPermissions(userId, "server.cron", "server.start")
                val token = tokenFor(userId)
                val serverId = createServer(createNode())

                val res = client.post("/api/servers/$serverId/jobs") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"RCON_COMMAND","cron_expression":"0 3 * * *","payload":"say hi"}""")
                }

                res.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("create with an invalid cron returns 422") {
            testApplication {
                testApp { _ -> configureJobsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalPermissions(userId, "server.cron", "server.start")
                val token = tokenFor(userId)
                val serverId = createServer(createNode())

                val res = client.post("/api/servers/$serverId/jobs") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"START","cron_expression":"not-a-cron"}""")
                }

                res.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        test("update toggles enabled") {
            testApplication {
                testApp { _ -> configureJobsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalPermissions(userId, "server.cron", "server.start")
                val token = tokenFor(userId)
                val serverId = createServer(createNode())

                val created = client.post("/api/servers/$serverId/jobs") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"START","cron_expression":"0 3 * * *"}""")
                }.body<JsonObject>()
                val jobId = created["id"]!!.jsonPrimitive.content

                val res = client.patch("/api/servers/$serverId/jobs/$jobId") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled":false}""")
                }

                res.status shouldBe HttpStatusCode.OK
                res.body<JsonObject>()["enabled"]!!.jsonPrimitive.content shouldBe "false"
            }
        }

        test("delete removes the job") {
            testApplication {
                testApp { _ -> configureJobsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalPermissions(userId, "server.cron", "server.start")
                val token = tokenFor(userId)
                val serverId = createServer(createNode())

                val created = client.post("/api/servers/$serverId/jobs") {
                    header("Authorization", "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"START","cron_expression":"0 3 * * *"}""")
                }.body<JsonObject>()
                val jobId = created["id"]!!.jsonPrimitive.content

                val res = client.delete("/api/servers/$serverId/jobs/$jobId") { header("Authorization", "Bearer $token") }

                res.status shouldBe HttpStatusCode.NoContent
            }
        }

        test("list job types returns the four schedulable types") {
            testApplication {
                testApp { _ -> configureJobsTest() }
                val client = jsonClient()
                val userId = createUser()
                val token = tokenFor(userId)

                val res = client.get("/api/system/job-types") { header("Authorization", "Bearer $token") }

                res.status shouldBe HttpStatusCode.OK
                res.body<JsonObject>()["types"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("START", "STOP", "RESTART", "RCON_COMMAND")
            }
        }
    })
