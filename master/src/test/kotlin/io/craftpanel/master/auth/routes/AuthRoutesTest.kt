package io.craftpanel.master.auth.routes

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.RefreshTokenService
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.util.toJavaUuid
import io.craftpanel.master.util.toKotlinUuid
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthRoutesTest {

    private val jwtConfig = JwtConfig(
        secret = "test-secret-that-is-at-least-32-characters!!",
        issuer = "craftpanel-test",
        audience = "craftpanel-test",
        expirySeconds = 900,
    )
    private val jwtManager = JwtManager(jwtConfig)
    private val refreshTokenService = RefreshTokenService()

    @BeforeTest
    fun setup() {
        TestDatabase.initIfNeeded()
        TestDatabase.reset()
    }

    private fun Application.configureTest() {
        install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
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
        routing { authRoutes(jwtManager, refreshTokenService) }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun createUser(
        username: String = "alice",
        email: String = "alice@example.com",
        password: String = "hunter2",
        isActive: Boolean = true,
    ): UUID = transaction {
        Users.insert {
            it[Users.username] = username
            it[Users.email] = email
            it[Users.passwordHash] = Argon2Hasher.hash(password)
            it[Users.isActive] = isActive
        }[Users.id].toJavaUuid()
    }

    private fun assignGlobalGroup(userId: UUID, groupName: String) = transaction {
        val groupId = Groups.selectAll().where { Groups.name eq groupName }.first()[Groups.id]
        UserGroupAssignments.insert {
            it[UserGroupAssignments.userId] = userId.toKotlinUuid()
            it[UserGroupAssignments.groupId] = groupId
            it[UserGroupAssignments.scopeType] = "GLOBAL"
        }
    }

    private fun HttpResponse.refreshTokenCookie(): String? =
        headers.getAll(HttpHeaders.SetCookie)
            ?.find { it.startsWith("refresh_token=") }
            ?.split(";")?.first()?.removePrefix("refresh_token=")
            ?.takeIf { it.isNotEmpty() }

    // Performs a full login and returns (accessToken, refreshTokenCookie).
    private suspend fun ApplicationTestBuilder.login(
        email: String = "alice@example.com",
        password: String = "hunter2",
    ): Pair<String, String> {
        val response = jsonClient().post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email, password))
        }
        return response.body<LoginResponse>().accessToken to response.refreshTokenCookie()!!
    }

    // -------------------------------------------------------------------------
    // login
    // -------------------------------------------------------------------------

    @Test
    fun `login with valid credentials returns access token, expires_in, and refresh cookie`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        createUser()

        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("alice@example.com", "hunter2"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<LoginResponse>()
        assertTrue(body.accessToken.isNotBlank())
        assertEquals(900L, body.expiresIn)
        assertNotNull(response.refreshTokenCookie())
    }

    @Test
    fun `login with wrong password returns 401`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        createUser()

        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("alice@example.com", "wrongpassword"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `login with unknown email returns 401`() = testApplication {
        application { configureTest() }
        val client = jsonClient()

        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("nobody@example.com", "password"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `login with inactive account returns 401`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        createUser(isActive = false)

        val response = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("alice@example.com", "hunter2"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // -------------------------------------------------------------------------
    // refresh
    // -------------------------------------------------------------------------

    @Test
    fun `refresh with valid cookie returns new access token and rotated cookie`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        createUser()

        val (_, firstRefreshToken) = login()

        val refreshResponse = client.post("/api/v1/auth/refresh") {
            cookie("refresh_token", firstRefreshToken)
        }

        assertEquals(HttpStatusCode.OK, refreshResponse.status)
        val body = refreshResponse.body<LoginResponse>()
        assertTrue(body.accessToken.isNotBlank())
        assertEquals(900L, body.expiresIn)
        val newRefreshToken = refreshResponse.refreshTokenCookie()
        assertNotNull(newRefreshToken)
        assertNotEquals(firstRefreshToken, newRefreshToken)
    }

    @Test
    fun `refresh without cookie returns 401`() = testApplication {
        application { configureTest() }

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/refresh").status)
    }

    @Test
    fun `refresh with garbage token returns 401`() = testApplication {
        application { configureTest() }

        val response = client.post("/api/v1/auth/refresh") {
            cookie("refresh_token", "not-a-real-token")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `refresh token cannot be reused after rotation`() = testApplication {
        application { configureTest() }
        createUser()

        val (_, token) = login()

        client.post("/api/v1/auth/refresh") { cookie("refresh_token", token) }

        val replayResponse = client.post("/api/v1/auth/refresh") {
            cookie("refresh_token", token)
        }

        assertEquals(HttpStatusCode.Unauthorized, replayResponse.status)
    }

    // -------------------------------------------------------------------------
    // logout
    // -------------------------------------------------------------------------

    @Test
    fun `logout requires valid JWT`() = testApplication {
        application { configureTest() }

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/logout").status)
    }

    @Test
    fun `logout clears the refresh cookie`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        createUser()

        val (accessToken, refreshToken) = login()

        val logoutResponse = client.post("/api/v1/auth/logout") {
            bearerAuth(accessToken)
            cookie("refresh_token", refreshToken)
        }

        assertEquals(HttpStatusCode.NoContent, logoutResponse.status)
        val setCookieHeader = logoutResponse.headers.getAll(HttpHeaders.SetCookie)
            ?.find { it.startsWith("refresh_token=") }
        assertNotNull(setCookieHeader)
        assertTrue(setCookieHeader.contains("Max-Age=0"))
    }

    @Test
    fun `logout invalidates the refresh token`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        createUser()

        val (accessToken, refreshToken) = login()

        client.post("/api/v1/auth/logout") {
            bearerAuth(accessToken)
            cookie("refresh_token", refreshToken)
        }

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/v1/auth/refresh") { cookie("refresh_token", refreshToken) }.status,
        )
    }

    @Test
    fun `logout with valid JWT but no cookie returns 204`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        createUser()

        val (accessToken, _) = login()

        assertEquals(
            HttpStatusCode.NoContent,
            client.post("/api/v1/auth/logout") { bearerAuth(accessToken) }.status,
        )
    }

    // -------------------------------------------------------------------------
    // logout-all
    // -------------------------------------------------------------------------

    @Test
    fun `logout-all revokes all refresh tokens`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        createUser()

        val (accessToken, refreshToken) = login()

        assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/auth/logout-all") { bearerAuth(accessToken) }.status)

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/v1/auth/refresh") { cookie("refresh_token", refreshToken) }.status,
        )
    }

    @Test
    fun `logout-all without JWT returns 401`() = testApplication {
        application { configureTest() }

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/logout-all").status)
    }

    // -------------------------------------------------------------------------
    // me
    // -------------------------------------------------------------------------

    @Test
    fun `me returns authenticated user data`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        createUser()

        val (accessToken, _) = login()

        val meResponse = client.get("/api/v1/auth/me") { bearerAuth(accessToken) }

        assertEquals(HttpStatusCode.OK, meResponse.status)
        val me = meResponse.body<MeResponse>()
        assertEquals("alice", me.username)
        assertEquals("alice@example.com", me.email)
        assertTrue(me.permissions.isEmpty())
    }

    @Test
    fun `me returns live permissions for group assignment`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Viewer")

        val (accessToken, _) = login()

        val me = client.get("/api/v1/auth/me") { bearerAuth(accessToken) }.body<MeResponse>()

        assertEquals(listOf("server.view"), me.permissions)
        assertEquals(listOf("Viewer"), me.groups)
    }

    @Test
    fun `me without JWT returns 401`() = testApplication {
        application { configureTest() }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/me").status)
    }

    @Test
    fun `me returns 401 when user is deactivated after token issuance`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()

        val (accessToken, _) = login()

        transaction {
            Users.update({ Users.id eq userId.toKotlinUuid() }) { it[Users.isActive] = false }
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/me") { bearerAuth(accessToken) }.status)
    }
}
