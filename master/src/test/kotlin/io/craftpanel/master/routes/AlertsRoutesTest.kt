package io.craftpanel.master.routes

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.TokenClaims
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.service.*
import io.craftpanel.master.util.toKotlinUuid
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
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import io.craftpanel.master.service.ForbiddenException as ServiceForbiddenException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class AlertsRoutesTest {

    private val jwtConfig = JwtConfig(
        secret = "test-secret-that-is-at-least-32-characters!!",
        issuer = "craftpanel-test",
        audience = "craftpanel-test",
        expirySeconds = 900,
    )
    private val jwtManager = JwtManager(jwtConfig)

    @BeforeTest
    fun setup() {
        TestDatabase.initIfNeeded()
        TestDatabase.reset()
    }

    private fun Application.configureTest() {
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
        routing { alertsRoutes(AlertService()) }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun createUser(email: String = "admin@example.com"): UUID = transaction {
        Users.insert {
            it[Users.username] = email.substringBefore("@")
            it[Users.email] = email
            it[Users.passwordHash] = Argon2Hasher.hash("hunter2")
            it[Users.isActive] = true
        }[Users.id].let { UUID.fromString(it.toString()) }
    }

    private fun assignGlobalGroup(userId: UUID, groupName: String) = transaction {
        val groupId = Groups.selectAll()
            .where { Groups.name eq groupName }
            .first()[Groups.id]
        UserGroupAssignments.insert {
            it[UserGroupAssignments.userId] = userId.toKotlinUuid()
            it[UserGroupAssignments.groupId] = groupId
            it[UserGroupAssignments.scopeType] = "GLOBAL"
        }
    }

    private fun tokenFor(userId: UUID): String =
        jwtManager.generate(TokenClaims(userId = userId, name = "admin", email = "admin@example.com", groups = emptyList()))

    private fun createNode(hostname: String = "node-1"): UUID = transaction {
        Nodes.insert {
            it[Nodes.hostname] = hostname
            it[Nodes.displayName] = hostname
            it[Nodes.publicIp] = "1.2.3.4"
            it[Nodes.privateIp] = "10.0.0.1"
            it[Nodes.tokenHash] = "a".repeat(64)
            it[Nodes.status] = "ACTIVE"
            it[Nodes.totalRamMb] = 8192
            it[Nodes.totalCpuShares] = 1024
        }[Nodes.id].let { UUID.fromString(it.toString()) }
    }

    // ── List thresholds ───────────────────────────────────────────────────────

    @Test
    fun `list thresholds requires system-settings`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        // Operator group has no system.settings
        assignGlobalGroup(userId, "Operator")
        val token = tokenFor(userId)
        val res = client.get("/api/alerts/thresholds") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun `list thresholds returns empty list when none exist`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val res = client.get("/api/alerts/thresholds") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.body<JsonObject>()
        assertEquals(0, body["thresholds"]!!.jsonArray.size)
    }

    // ── Create threshold ──────────────────────────────────────────────────────

    @Test
    fun `create threshold with numeric value succeeds`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()

        val res = client.post("/api/alerts/thresholds") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"scope_type":"NODE","scope_id":"$nodeId","metric":"cpu_percent","threshold_value":80.0}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        val body = res.body<JsonObject>()
        assertEquals("NODE", body["scope_type"]!!.jsonPrimitive.content)
        assertEquals("cpu_percent", body["metric"]!!.jsonPrimitive.content)
        assertEquals(80.0, body["threshold_value"]!!.jsonPrimitive.content.toDouble())
        assertNull(body["threshold_state"]?.jsonPrimitive?.content?.takeIf { it != "null" })
    }

    @Test
    fun `create threshold with state value succeeds`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()

        val res = client.post("/api/alerts/thresholds") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"scope_type":"NODE","scope_id":"$nodeId","metric":"server_health","threshold_state":"UNHEALTHY"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        val body = res.body<JsonObject>()
        assertEquals("UNHEALTHY", body["threshold_state"]!!.jsonPrimitive.content)
        assertNull(body["threshold_value"]?.jsonPrimitive?.content?.takeIf { it != "null" }
            ?.toDoubleOrNull())
    }

    @Test
    fun `create threshold rejects both value and state`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()

        val res = client.post("/api/alerts/thresholds") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"scope_type":"NODE","scope_id":"$nodeId","metric":"cpu_percent","threshold_value":80.0,"threshold_state":"UNHEALTHY"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
    }

    @Test
    fun `create threshold rejects neither value nor state`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()

        val res = client.post("/api/alerts/thresholds") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"scope_type":"NODE","scope_id":"$nodeId","metric":"cpu_percent"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
    }

    // ── Delete threshold ──────────────────────────────────────────────────────

    @Test
    fun `delete threshold removes it and its events`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()

        // Create threshold
        val thresholdId = transaction {
            AlertThresholds.insert {
                it[AlertThresholds.scopeType] = "NODE"
                it[AlertThresholds.scopeId] = nodeId.toKotlinUuid()
                it[AlertThresholds.metric] = "cpu_percent"
                it[AlertThresholds.thresholdValue] = 90.0
            }[AlertThresholds.id]
        }

        // Create associated event
        transaction {
            AlertEvents.insert {
                it[AlertEvents.thresholdId] = thresholdId
                it[AlertEvents.message] = "test event"
                it[AlertEvents.firedAt] = Clock.System.now()
                    .toLocalDateTime(TimeZone.UTC)
            }
        }

        val res = client.delete("/api/alerts/thresholds/$thresholdId") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.NoContent, res.status)

        // Threshold and its events should be gone
        transaction {
            assertEquals(
                0,
                AlertThresholds.selectAll()
                    .where { AlertThresholds.id eq thresholdId }
                    .count()
            )
            assertEquals(
                0,
                AlertEvents.selectAll()
                    .where { AlertEvents.thresholdId eq thresholdId }
                    .count()
            )
        }
    }

    @Test
    fun `delete nonexistent threshold returns 404`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)

        val res = client.delete("/api/alerts/thresholds/${UUID.randomUUID()}") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    // ── List events ───────────────────────────────────────────────────────────

    @Test
    fun `list events returns all events`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)

        val thresholdId = transaction {
            AlertThresholds.insert {
                it[AlertThresholds.scopeType] = "NODE"
                it[AlertThresholds.scopeId] = nodeId.toKotlinUuid()
                it[AlertThresholds.metric] = "ram_percent"
                it[AlertThresholds.thresholdValue] = 85.0
            }[AlertThresholds.id]
        }

        transaction {
            AlertEvents.insert {
                it[AlertEvents.thresholdId] = thresholdId
                it[AlertEvents.message] = "Node node-1: ram_percent at 90.0%"
                it[AlertEvents.firedAt] = now
                it[AlertEvents.resolvedAt] = now
            }
            AlertEvents.insert {
                it[AlertEvents.thresholdId] = thresholdId
                it[AlertEvents.message] = "Node node-1: ram_percent at 88.0%"
                it[AlertEvents.firedAt] = now
            }
        }

        val res = client.get("/api/alerts/events") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.OK, res.status)
        val events = res.body<JsonObject>()["events"]!!.jsonArray
        assertEquals(2, events.size)
    }

    @Test
    fun `list events active_only filters resolved events`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)

        val thresholdId = transaction {
            AlertThresholds.insert {
                it[AlertThresholds.scopeType] = "NODE"
                it[AlertThresholds.scopeId] = nodeId.toKotlinUuid()
                it[AlertThresholds.metric] = "cpu_percent"
                it[AlertThresholds.thresholdValue] = 80.0
            }[AlertThresholds.id]
        }

        transaction {
            AlertEvents.insert {
                it[AlertEvents.thresholdId] = thresholdId
                it[AlertEvents.message] = "resolved"
                it[AlertEvents.firedAt] = now
                it[AlertEvents.resolvedAt] = now
            }
            AlertEvents.insert {
                it[AlertEvents.thresholdId] = thresholdId
                it[AlertEvents.message] = "open"
                it[AlertEvents.firedAt] = now
            }
        }

        val res = client.get("/api/alerts/events?active_only=true") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.OK, res.status)
        val events = res.body<JsonObject>()["events"]!!.jsonArray
        assertEquals(1, events.size)
        assertEquals("open", events[0].jsonObject["message"]!!.jsonPrimitive.content)
    }
}
