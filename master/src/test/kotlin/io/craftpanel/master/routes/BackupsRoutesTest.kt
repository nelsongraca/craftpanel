package io.craftpanel.master.routes

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.TokenClaims
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.grpc.BulkDataServiceImpl
import io.craftpanel.master.grpc.ControlServiceImpl
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.service.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid
import io.craftpanel.master.service.ForbiddenException as ServiceForbiddenException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class BackupsRoutesTest : FunSpec({
    val jwtConfig = JwtConfig(
        secret = "test-secret-that-is-at-least-32-characters!!",
        issuer = "craftpanel-test",
        audience = "craftpanel-test",
        expirySeconds = 900,
    )
    val jwtManager = JwtManager(jwtConfig)
    val noopControlSvc = ControlServiceImpl(NodeConfig("test-token", 50052))
    val noopProxy = DataServiceProxy(noopControlSvc, BulkDataServiceImpl(noopControlSvc))
    val noopSend: (String, io.craftpanel.proto.MasterMessage) -> Boolean = { _, _ -> true }

    beforeTest {
        TestDatabase.initIfNeeded()
        TestDatabase.reset()
    }

    fun Application.configureTest() {
        install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(StatusPages) {
            exception<NotFoundException> { call, ex -> call.respond(HttpStatusCode.NotFound, mapOf("error" to (ex.message ?: "Not found"))) }
            exception<ServiceForbiddenException> { call, ex -> call.respond(HttpStatusCode.Forbidden, mapOf("error" to (ex.message ?: "Forbidden"))) }
            exception<ConflictException> { call, ex -> call.respond(HttpStatusCode.Conflict, mapOf("error" to (ex.message ?: "Conflict"))) }
            exception<UnprocessableException> { call, ex -> call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to (ex.message ?: "Unprocessable"))) }
            exception<BadGatewayException> { call, ex -> call.respond(HttpStatusCode.BadGateway, mapOf("error" to (ex.message ?: "Bad gateway"))) }
            exception<BadRequestException> { call, ex -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to (ex.message ?: "Bad request"))) }
        }
        install(Authentication) {
            jwt("auth-jwt") {
                realm = "CraftPanel"
                verifier(jwtManager.verifier)
                validate { credential ->
                    if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null
                }
                challenge { _, _ ->
                    call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "Unauthorized"))
                }
            }
        }
        routing { backupsRoutes(BackupService(noopSend, noopProxy)) }
    }

    fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
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

    // ── List backups ──────────────────────────────────────────────────────────

    test("list backups returns empty list when none exist") {
        testApplication {
            application { configureTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val token = tokenFor(userId)
            val nodeId = createNode()
            val serverId = createServer(nodeId)

            val res = client.get("/api/servers/$serverId/backups") { header("Authorization", "Bearer $token") }
            res.status shouldBe HttpStatusCode.OK
            res.body<JsonObject>()["backups"]!!.jsonArray.size shouldBe 0
        }
    }

    test("list backups requires server_backup permission") {
        testApplication {
            application { configureTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Viewer")
            val token = tokenFor(userId)
            val nodeId = createNode()
            val serverId = createServer(nodeId)

            val res = client.get("/api/servers/$serverId/backups") { header("Authorization", "Bearer $token") }
            res.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("list backups returns 404 for unknown server") {
        testApplication {
            application { configureTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val token = tokenFor(userId)

            val res = client.get("/api/servers/${Uuid.random()}/backups") { header("Authorization", "Bearer $token") }
            res.status shouldBe HttpStatusCode.NotFound
        }
    }

    // ── Trigger backup ────────────────────────────────────────────────────────

    test("trigger backup creates IN_PROGRESS backup") {
        testApplication {
            application { configureTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val token = tokenFor(userId)
            val nodeId = createNode()
            val serverId = createServer(nodeId)

            val res = client.post("/api/servers/$serverId/backups") {
                header("Authorization", "Bearer $token")
            }
            res.status shouldBe HttpStatusCode.Accepted
            val body = res.body<JsonObject>()
            body["trigger"]!!.jsonPrimitive.content shouldBe "MANUAL"
            body["status"]!!.jsonPrimitive.content shouldBe "IN_PROGRESS"

            val count = transaction {
                Backups.selectAll()
                    .where { Backups.serverId eq serverId }
                    .count()
            }
            count shouldBe 1
        }
    }

    test("trigger backup enforces retention by deleting oldest completed") {
        testApplication {
            application { configureTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val token = tokenFor(userId)
            val nodeId = createNode()
            val serverId = createServer(nodeId)

            transaction {
                Servers.update({ Servers.id eq serverId }) {
                    it[Servers.backupMaxCount] = 2
                }
            }

            val now = Clock.System.now()
                .toLocalDateTime(TimeZone.UTC)
            transaction {
                repeat(2) { i ->
                    Backups.insert {
                        it[Backups.serverId] = serverId
                        it[Backups.nodeId] = nodeId
                        it[Backups.trigger] = "MANUAL"
                        it[Backups.status] = "COMPLETED"
                        it[Backups.filePath] = "/data/backups/backup-$i.tar.gz"
                        it[Backups.completedAt] = now
                    }
                }
            }

            val res = client.post("/api/servers/$serverId/backups") {
                header("Authorization", "Bearer $token")
            }
            res.status shouldBe HttpStatusCode.Accepted

            val total = transaction {
                Backups.selectAll()
                    .where { Backups.serverId eq serverId }
                    .count()
            }
            total shouldBe 2
        }
    }

    // ── Delete backup ─────────────────────────────────────────────────────────

    test("delete completed backup returns 204") {
        testApplication {
            application { configureTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val token = tokenFor(userId)
            val nodeId = createNode()
            val serverId = createServer(nodeId)
            val now = Clock.System.now()
                .toLocalDateTime(TimeZone.UTC)

            val backupId = transaction {
                Backups.insert {
                    it[Backups.serverId] = serverId
                    it[Backups.nodeId] = nodeId
                    it[Backups.trigger] = "MANUAL"
                    it[Backups.status] = "COMPLETED"
                    it[Backups.filePath] = "/data/backups/test.tar.gz"
                    it[Backups.completedAt] = now
                }[Backups.id]
            }

            val res = client.delete("/api/servers/$serverId/backups/$backupId") {
                header("Authorization", "Bearer $token")
            }
            res.status shouldBe HttpStatusCode.NoContent
            transaction {
                Backups.selectAll()
                    .where { Backups.id eq backupId }
                    .count() shouldBe 0
            }
        }
    }

    test("delete in-progress backup returns 409") {
        testApplication {
            application { configureTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val token = tokenFor(userId)
            val nodeId = createNode()
            val serverId = createServer(nodeId)

            val backupId = transaction {
                Backups.insert {
                    it[Backups.serverId] = serverId
                    it[Backups.nodeId] = nodeId
                    it[Backups.trigger] = "MANUAL"
                    it[Backups.status] = "IN_PROGRESS"
                }[Backups.id]
            }

            val res = client.delete("/api/servers/$serverId/backups/$backupId") {
                header("Authorization", "Bearer $token")
            }
            res.status shouldBe HttpStatusCode.Conflict
        }
    }

    // ── Backup schedule ───────────────────────────────────────────────────────

    test("get backup schedule returns defaults") {
        testApplication {
            application { configureTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val token = tokenFor(userId)
            val nodeId = createNode()
            val serverId = createServer(nodeId)

            val res = client.get("/api/servers/$serverId/backup-schedule") {
                header("Authorization", "Bearer $token")
            }
            res.status shouldBe HttpStatusCode.OK
            val body = res.body<JsonObject>()
            body["backup_max_count"]!!.jsonPrimitive.content.toInt() shouldBe 10
        }
    }

    test("put backup schedule with valid cron succeeds") {
        testApplication {
            application { configureTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val token = tokenFor(userId)
            val nodeId = createNode()
            val serverId = createServer(nodeId)

            val res = client.put("/api/servers/$serverId/backup-schedule") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"backup_schedule":"0 2 * * *","backup_max_count":5}""")
            }
            res.status shouldBe HttpStatusCode.OK

            val row = transaction {
                Servers.selectAll()
                    .where { Servers.id eq serverId }
                    .first()
            }
            row[Servers.backupSchedule] shouldBe "0 2 * * *"
            row[Servers.backupMaxCount] shouldBe 5
        }
    }

    test("put backup schedule with invalid cron returns 422") {
        testApplication {
            application { configureTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val token = tokenFor(userId)
            val nodeId = createNode()
            val serverId = createServer(nodeId)

            val res = client.put("/api/servers/$serverId/backup-schedule") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"backup_schedule":"not-a-cron"}""")
            }
            res.status shouldBe HttpStatusCode.UnprocessableEntity
        }
    }

    test("put backup schedule with null clears schedule") {
        testApplication {
            application { configureTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val token = tokenFor(userId)
            val nodeId = createNode()
            val serverId = createServer(nodeId)

            val res = client.put("/api/servers/$serverId/backup-schedule") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"backup_schedule":null}""")
            }
            res.status shouldBe HttpStatusCode.OK
        }
    }
})
