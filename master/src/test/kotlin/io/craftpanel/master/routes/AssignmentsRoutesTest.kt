package io.craftpanel.master.routes

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.TokenClaims
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.service.*
import kotlin.uuid.Uuid
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
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import io.craftpanel.master.service.ForbiddenException as ServiceForbiddenException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class AssignmentsRoutesTest {

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
                    call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "Token is not valid or has expired"))
                }
            }
        }
        routing { assignmentsRoutes(AssignmentService()) }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun createUser(username: String = "admin", email: String = "admin@example.com"): Uuid = transaction {
        Users.insert {
            it[Users.username] = username
            it[Users.email] = email
            it[Users.passwordHash] = Argon2Hasher.hash("pass")
        }[Users.id].let { Uuid.parse(it.toString()) }
    }

    private fun assignGlobalGroup(userId: Uuid, groupName: String): Uuid = transaction {
        val groupId = Groups.selectAll()
            .where { Groups.name eq groupName }
            .first()[Groups.id]
        UserGroupAssignments.insert {
            it[UserGroupAssignments.userId] = userId
            it[UserGroupAssignments.groupId] = groupId
            it[UserGroupAssignments.scopeType] = "GLOBAL"
        }[UserGroupAssignments.id].let { Uuid.parse(it.toString()) }
    }

    private fun groupId(name: String): Uuid = transaction {
        Groups.selectAll()
            .where { Groups.name eq name }
            .first()[Groups.id].let { Uuid.parse(it.toString()) }
    }

    private fun tokenFor(userId: Uuid): String =
        jwtManager.generate(TokenClaims(userId = userId, name = "admin", email = "admin@example.com", groups = emptyList()))

    // ── GET /api/users/{userId}/assignments ───────────────────────────────────

    @Test
    fun `listUserAssignments returns 403 without permission`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        val targetId = createUser(username = "target", email = "target@example.com")

        assertEquals(HttpStatusCode.Forbidden, client.get("/api/users/$targetId/assignments") { bearerAuth(tokenFor(userId)) }.status)
    }

    @Test
    fun `listUserAssignments returns 404 for unknown user`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")

        assertEquals(HttpStatusCode.NotFound, client.get("/api/users/${Uuid.random()}/assignments") { bearerAuth(tokenFor(userId)) }.status)
    }

    @Test
    fun `listUserAssignments returns assignments for user`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val targetId = createUser(username = "target", email = "target@example.com")
        assignGlobalGroup(targetId, "Viewer")
        val client = jsonClient()

        val body = client.get("/api/users/$targetId/assignments") { bearerAuth(tokenFor(userId)) }
            .body<JsonObject>()
        val assignments = body["assignments"]!!.jsonArray
        assertEquals(1, assignments.size)
        assertEquals("GLOBAL", assignments[0].jsonObject["scope_type"]!!.jsonPrimitive.content)
    }

    // ── POST /api/users/{userId}/assignments ──────────────────────────────────

    @Test
    fun `createAssignment returns 403 without permission`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        val response = client.post("/api/users/$userId/assignments") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"group_id":"${Uuid.random()}","scope_type":"GLOBAL"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `createAssignment creates GLOBAL assignment`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val targetId = createUser(username = "target", email = "target@example.com")
        val gId = groupId("Viewer")
        val client = jsonClient()

        val body = client.post("/api/users/$targetId/assignments") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"group_id":"$gId","scope_type":"GLOBAL"}""")
        }
            .body<JsonObject>()

        assertNotNull(body["id"])
        assertEquals("GLOBAL", body["scope_type"]!!.jsonPrimitive.content)
        assertEquals(gId.toString(), body["group_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `createAssignment returns 409 for duplicate assignment`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val targetId = createUser(username = "target", email = "target@example.com")
        val gId = groupId("Viewer")
        assignGlobalGroup(targetId, "Viewer")

        val response = client.post("/api/users/$targetId/assignments") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"group_id":"$gId","scope_type":"GLOBAL"}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `createAssignment returns 422 for NETWORK scope without scope_id`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val targetId = createUser(username = "target", email = "target@example.com")
        val gId = groupId("Viewer")

        val response = client.post("/api/users/$targetId/assignments") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"group_id":"$gId","scope_type":"NETWORK"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    // ── DELETE /api/users/{userId}/assignments/{assignmentId} ─────────────────

    @Test
    fun `deleteAssignment returns 403 without permission`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        val targetId = createUser(username = "target", email = "target@example.com")
        val assignmentId = assignGlobalGroup(targetId, "Viewer")

        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/users/$targetId/assignments/$assignmentId") { bearerAuth(tokenFor(userId)) }.status)
    }

    @Test
    fun `deleteAssignment removes assignment and returns 204`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val targetId = createUser(username = "target", email = "target@example.com")
        val assignmentId = assignGlobalGroup(targetId, "Viewer")

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/users/$targetId/assignments/$assignmentId") { bearerAuth(tokenFor(userId)) }.status)

        val exists = transaction {
            UserGroupAssignments.selectAll()
                .where { UserGroupAssignments.id eq assignmentId }
                .firstOrNull() != null
        }
        assertEquals(false, exists)
    }

    @Test
    fun `deleteAssignment returns 404 for unknown assignment`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val targetId = createUser(username = "target", email = "target@example.com")

        assertEquals(HttpStatusCode.NotFound, client.delete("/api/users/$targetId/assignments/${Uuid.random()}") { bearerAuth(tokenFor(userId)) }.status)
    }
}
