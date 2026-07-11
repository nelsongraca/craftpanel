package io.craftpanel.master.auth.routes

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.auth.*
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.service.repo.UserRepositoryImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration
import kotlin.uuid.Uuid
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class AuthRoutesTest :
    FunSpec({
        val jwtConfig = JwtConfig(
            secret = "test-secret-that-is-at-least-32-characters!!",
            issuer = "craftpanel-test",
            audience = "craftpanel-test",
            expirySeconds = 900
        )
        val jwtManager = JwtManager(jwtConfig)
        val userRepository = UserRepositoryImpl()
        val refreshTokenService = RefreshTokenService(userRepository)

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        fun Application.configureTest() {
            install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(RateLimit) {
                register(RateLimitName("auth-login")) { rateLimiter(limit = 1000, refillPeriod = Duration.INFINITE) }
                register(RateLimitName("auth-refresh")) { rateLimiter(limit = 1000, refillPeriod = Duration.INFINITE) }
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
            routing { authRoutes(jwtManager, refreshTokenService, WsTicketService(), userRepository) }
        }

        fun ApplicationTestBuilder.jsonClient() = createClient {
            install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        fun createUser(username: String = "alice", email: String = "alice@example.com", password: String = "hunter2", isActive: Boolean = true): Uuid = transaction {
            Users.insert {
                it[Users.username] = username
                it[Users.email] = email
                it[Users.passwordHash] = Argon2Hasher.hash(password)
                it[Users.isActive] = isActive
            }[Users.id]
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

        fun HttpResponse.refreshTokenCookie(): String? = headers.getAll(HttpHeaders.SetCookie)
            ?.find { it.startsWith("refresh_token=") }
            ?.split(";")
            ?.first()
            ?.removePrefix("refresh_token=")
            ?.takeIf { it.isNotEmpty() }

        suspend fun ApplicationTestBuilder.login(email: String = "alice@example.com", password: String = "hunter2"): Pair<String, String> {
            val response = jsonClient().post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(email, password))
            }
            return response.body<LoginResponse>().accessToken to response.refreshTokenCookie()!!
        }

        // -------------------------------------------------------------------------
        // login
        // -------------------------------------------------------------------------

        test("login with valid credentials returns access token, expires_in, and refresh cookie") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val response = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice@example.com", "hunter2"))
                }

                response.status shouldBe HttpStatusCode.OK
                val body = response.body<LoginResponse>()
                body.accessToken.isNotBlank() shouldBe true
                body.expiresIn shouldBe 900L
                response.refreshTokenCookie() shouldNotBe null
            }
        }

        test("login with wrong password returns 401") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val response = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice@example.com", "wrongpassword"))
                }

                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("login with unknown email returns 401") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()

                val response = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("nobody@example.com", "password"))
                }

                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("login with inactive account returns 401") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser(isActive = false)

                val response = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice@example.com", "hunter2"))
                }

                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        // -------------------------------------------------------------------------
        // refresh
        // -------------------------------------------------------------------------

        test("refresh with valid cookie returns new access token and rotated cookie") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (_, firstRefreshToken) = login()

                val refreshResponse = client.post("/api/auth/refresh") {
                    cookie("refresh_token", firstRefreshToken)
                }

                refreshResponse.status shouldBe HttpStatusCode.OK
                val body = refreshResponse.body<LoginResponse>()
                body.accessToken.isNotBlank() shouldBe true
                body.expiresIn shouldBe 900L
                val newRefreshToken = refreshResponse.refreshTokenCookie()
                newRefreshToken shouldNotBe null
                newRefreshToken shouldNotBe firstRefreshToken
            }
        }

        test("refresh without cookie returns 401") {
            testApplication {
                application { configureTest() }
                client.post("/api/auth/refresh").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("refresh with garbage token returns 401") {
            testApplication {
                application { configureTest() }

                val response = client.post("/api/auth/refresh") {
                    cookie("refresh_token", "not-a-real-token")
                }

                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("refresh token cannot be reused after rotation") {
            testApplication {
                application { configureTest() }
                createUser()

                val (_, token) = login()

                client.post("/api/auth/refresh") { cookie("refresh_token", token) }

                val replayResponse = client.post("/api/auth/refresh") {
                    cookie("refresh_token", token)
                }

                replayResponse.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        // -------------------------------------------------------------------------
        // logout
        // -------------------------------------------------------------------------

        test("logout requires valid JWT") {
            testApplication {
                application { configureTest() }
                client.post("/api/auth/logout").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("logout clears the refresh cookie") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, refreshToken) = login()

                val logoutResponse = client.post("/api/auth/logout") {
                    bearerAuth(accessToken)
                    cookie("refresh_token", refreshToken)
                }

                logoutResponse.status shouldBe HttpStatusCode.NoContent
                val setCookieHeader = logoutResponse.headers.getAll(HttpHeaders.SetCookie)
                    ?.find { it.startsWith("refresh_token=") }
                setCookieHeader shouldNotBe null
                setCookieHeader!! shouldContain "Max-Age=0"
            }
        }

        test("logout invalidates the refresh token") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, refreshToken) = login()

                client.post("/api/auth/logout") {
                    bearerAuth(accessToken)
                    cookie("refresh_token", refreshToken)
                }

                client.post("/api/auth/refresh") { cookie("refresh_token", refreshToken) }
                    .status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("logout with valid JWT but no cookie returns 204") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, _) = login()

                client.post("/api/auth/logout") { bearerAuth(accessToken) }
                    .status shouldBe HttpStatusCode.NoContent
            }
        }

        // -------------------------------------------------------------------------
        // logout-all
        // -------------------------------------------------------------------------

        test("logout-all revokes all refresh tokens") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, refreshToken) = login()

                client.post("/api/auth/logout-all") { bearerAuth(accessToken) }
                    .status shouldBe HttpStatusCode.NoContent

                client.post("/api/auth/refresh") { cookie("refresh_token", refreshToken) }
                    .status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("logout-all without JWT returns 401") {
            testApplication {
                application { configureTest() }
                client.post("/api/auth/logout-all").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        // -------------------------------------------------------------------------
        // me
        // -------------------------------------------------------------------------

        test("me returns authenticated user data") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, _) = login()

                val meResponse = client.get("/api/auth/me") { bearerAuth(accessToken) }

                meResponse.status shouldBe HttpStatusCode.OK
                val me = meResponse.body<MeResponse>()
                me.username shouldBe "alice"
                me.email shouldBe "alice@example.com"
                me.permissions.isEmpty() shouldBe true
            }
        }

        test("me returns live permissions for group assignment") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")

                val (accessToken, _) = login()

                val me = client.get("/api/auth/me") { bearerAuth(accessToken) }
                    .body<MeResponse>()

                me.permissions shouldBe listOf("server.view")
                me.groups shouldBe listOf("Viewer")
            }
        }

        test("me without JWT returns 401") {
            testApplication {
                application { configureTest() }
                client.get("/api/auth/me").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("me returns 401 when user is deactivated after token issuance") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                val userId = createUser()

                val (accessToken, _) = login()

                transaction {
                    Users.update({ Users.id eq userId }) { it[Users.isActive] = false }
                }

                client.get("/api/auth/me") { bearerAuth(accessToken) }.status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
