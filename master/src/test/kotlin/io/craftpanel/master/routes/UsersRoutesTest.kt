package io.craftpanel.master.routes

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.TokenClaims
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.GroupPermissions
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.service.UserService
import io.craftpanel.master.util.toKotlinUuid
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.response.*
import io.ktor.server.plugins.statuspages.StatusPages
import io.craftpanel.master.service.BadGatewayException
import io.craftpanel.master.service.BadRequestException
import io.craftpanel.master.service.ConflictException
import io.craftpanel.master.service.ForbiddenException as ServiceForbiddenException
import io.craftpanel.master.service.NotFoundException
import io.craftpanel.master.service.UnprocessableException
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UsersRoutesTest {

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
        routing { usersRoutes(UserService()) }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun createUser(
        username: String = "admin",
        email: String = "admin@example.com",
        password: String = "hunter2",
        isActive: Boolean = true,
    ): UUID = transaction {
        Users.insert {
            it[Users.username] = username
            it[Users.email] = email
            it[Users.passwordHash] = Argon2Hasher.hash(password)
            it[Users.isActive] = isActive
        }[Users.id].let { UUID.fromString(it.toString()) }
    }

    private fun assignGlobalGroup(userId: UUID, groupName: String) = transaction {
        val groupId = Groups.selectAll().where { Groups.name eq groupName }.first()[Groups.id]
        UserGroupAssignments.insert {
            it[UserGroupAssignments.userId] = userId.toKotlinUuid()
            it[UserGroupAssignments.groupId] = groupId
            it[UserGroupAssignments.scopeType] = "GLOBAL"
        }
    }

    private fun tokenFor(userId: UUID, username: String = "admin"): String =
        jwtManager.generate(TokenClaims(userId = userId, name = username, email = "$username@example.com", groups = emptyList()))

    // ── GET /api/users ────────────────────────────────────────────────────────

    @Test
    fun `listUsers returns 403 without system_users permission`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        val response = client.get("/api/users") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `listUsers returns wrapped list for super admin`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        createUser(username = "other", email = "other@example.com")
        val client = jsonClient()

        val body = client.get("/api/users") { bearerAuth(tokenFor(userId)) }.body<JsonObject>()
        val users = body["users"]!!.jsonArray
        assertEquals(2, users.size)
        assertNotNull(users[0].jsonObject["created_at"])
        assertNotNull(users[0].jsonObject["is_active"])
    }

    // ── POST /api/users ───────────────────────────────────────────────────────

    @Test
    fun `createUser returns 403 without permission`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        val response = client.post("/api/users") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"username":"new","email":"new@x.com","password":"p"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `createUser returns full user object on 201`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val client = jsonClient()

        val body = client.post("/api/users") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"username":"newuser","email":"newuser@example.com","password":"secret"}""")
        }.body<JsonObject>()

        assertEquals("newuser", body["username"]!!.jsonPrimitive.content)
        assertNotNull(body["id"])
        assertNotNull(body["created_at"])
        assertEquals(true, body["is_active"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `createUser returns 422 for duplicate username`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")

        val response = client.post("/api/users") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","email":"different@example.com","password":"p"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    // ── GET /api/users/{id} ───────────────────────────────────────────────────

    @Test
    fun `getUser returns 404 for unknown id`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")

        assertEquals(HttpStatusCode.NotFound, client.get("/api/users/${UUID.randomUUID()}") { bearerAuth(tokenFor(userId)) }.status)
    }

    @Test
    fun `getUser returns user with created_at`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val client = jsonClient()

        val body = client.get("/api/users/$userId") { bearerAuth(tokenFor(userId)) }.body<JsonObject>()
        assertEquals(userId.toString(), body["id"]!!.jsonPrimitive.content)
        assertNotNull(body["created_at"])
    }

    // ── PATCH /api/users/{id} ─────────────────────────────────────────────────

    @Test
    fun `updateUser returns 403 without permission`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        val response = client.patch("/api/users/$userId") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"username":"new"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `updateUser updates email and is_active`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val targetId = createUser(username = "target", email = "target@example.com")
        val client = jsonClient()

        val body = client.patch("/api/users/$targetId") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"email":"updated@example.com","is_active":false}""")
        }.body<JsonObject>()

        assertEquals("updated@example.com", body["email"]!!.jsonPrimitive.content)
        assertEquals(false, body["is_active"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `updateUser returns 422 on duplicate email`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        createUser(username = "other", email = "other@example.com")

        val response = client.patch("/api/users/$userId") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"email":"other@example.com"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    @Test
    fun `updateUser returns 404 for unknown user`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")

        val response = client.patch("/api/users/${UUID.randomUUID()}") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"username":"x"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ── DELETE /api/users/{id} ────────────────────────────────────────────────

    @Test
    fun `deleteUser returns 403 without permission`() = testApplication {
        application { configureTest() }
        val userId = createUser()

        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/users/$userId") { bearerAuth(tokenFor(userId)) }.status)
    }

    @Test
    fun `deleteUser removes user and returns 204`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val targetId = createUser(username = "target", email = "target@example.com")

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/users/$targetId") { bearerAuth(tokenFor(userId)) }.status)

        val exists = transaction {
            Users.selectAll().where { Users.id eq targetId.toKotlinUuid() }.firstOrNull() != null
        }
        assertEquals(false, exists)
    }

    @Test
    fun `deleteUser returns 404 for unknown user`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")

        assertEquals(HttpStatusCode.NotFound, client.delete("/api/users/${UUID.randomUUID()}") { bearerAuth(tokenFor(userId)) }.status)
    }
}
