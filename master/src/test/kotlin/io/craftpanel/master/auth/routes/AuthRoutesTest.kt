package io.craftpanel.master.auth.routes

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.auth.*
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.crypto.SecretCipher
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.service.repo.impl.RecoveryCodeRepositoryImpl
import io.craftpanel.master.service.repo.impl.UserRepositoryImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldContain
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
        val recoveryCodeRepository = RecoveryCodeRepositoryImpl()
        val totpService = TotpService(
            SecretCipher(
                java.util.Base64.getDecoder()
                    .decode("Y2hhbmdlbWUtMzItYnl0ZXMtZGV2LWtleS1vbmx5ISE=")
            )
        )

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        fun Application.configureTest() {
            install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(RateLimit) {
                register(RateLimitName("auth-login")) { rateLimiter(limit = 1000, refillPeriod = Duration.INFINITE) }
                register(RateLimitName("auth-refresh")) { rateLimiter(limit = 1000, refillPeriod = Duration.INFINITE) }
                register(RateLimitName("auth-totp-verify")) { rateLimiter(limit = 1000, refillPeriod = Duration.INFINITE) }
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
            routing { authRoutes(jwtManager, refreshTokenService, TrustedDeviceService(userRepository), WsTicketService(), userRepository, totpService, recoveryCodeRepository) }
        }

        fun ApplicationTestBuilder.jsonClient() = createClient {
            install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        fun createUser(username: String = "alice", email: String = "alice@example.com", password: String = "hunter2", isActive: Boolean = true, mustChangePassword: Boolean = false): Uuid =
            transaction {
                Users.insert {
                    it[Users.username] = username
                    it[Users.email] = email
                    it[Users.passwordHash] = Argon2Hasher.hash(password)
                    it[Users.isActive] = isActive
                    it[Users.mustChangePassword] = mustChangePassword
                }[Users.id].value
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

        fun createNode(): Uuid = transaction {
            Nodes.insert {
                it[Nodes.hostname] = "node-1"
                it[Nodes.displayName] = "node-1"
                it[Nodes.publicIp] = "1.2.3.4"
                it[Nodes.privateIp] = "10.0.0.1"
                it[Nodes.tokenHash] = "a".repeat(64)
                it[Nodes.status] = "ACTIVE"
                it[Nodes.totalRamMb] = 8192
                it[Nodes.totalCpuShares] = 0
                it[Nodes.portRangeStart] = 25565
                it[Nodes.portRangeEnd] = 25600
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        fun createNetwork(): Uuid = transaction {
            ServerNetworks.insert {
                it[ServerNetworks.name] = "net-1"
            }[ServerNetworks.id].let { Uuid.parse(it.toString()) }
        }

        fun createServer(nodeId: Uuid, networkId: Uuid?): Uuid = transaction {
            Servers.insert {
                it[Servers.nodeId] = nodeId
                it[Servers.networkId] = networkId
                it[Servers.name] = "srv"
                it[Servers.displayName] = "srv"
                it[Servers.serverType] = "VANILLA"
                it[Servers.mcVersion] = "1.21.4"
                it[Servers.hostPort] = 25565
                it[Servers.memoryMb] = 1024
                it[Servers.cpuShares] = 0
                it[Servers.status] = "STOPPED"
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }

        fun assignScopedGroup(userId: Uuid, groupName: String, scopeType: String, scopeId: Uuid) = transaction {
            val groupId = Groups.selectAll()
                .where { Groups.name eq groupName }
                .first()[Groups.id]
            UserGroupAssignments.insert {
                it[UserGroupAssignments.userId] = userId
                it[UserGroupAssignments.groupId] = groupId
                it[UserGroupAssignments.scopeType] = scopeType
                it[UserGroupAssignments.scopeId] = scopeId
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
            val body = response.body<LoginResponse>()
            body.accessToken shouldNotBe null
            return body.accessToken!! to response.refreshTokenCookie()!!
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
                body.accessToken shouldNotBe null
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
                body.accessToken shouldNotBe null
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

        test("logout-all revokes all refresh tokens when no cookie present") {
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

        test("logout-all keeps the current session active when cookie is present") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, refreshToken) = login()

                client.post("/api/auth/logout-all") { bearerAuth(accessToken); cookie("refresh_token", refreshToken) }
                    .status shouldBe HttpStatusCode.NoContent

                client.post("/api/auth/refresh") { cookie("refresh_token", refreshToken) }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        test("logout-all without JWT returns 401") {
            testApplication {
                application { configureTest() }
                client.post("/api/auth/logout-all").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        // -------------------------------------------------------------------------
        // change-password
        // -------------------------------------------------------------------------

        test("change-password with correct old password returns 204 and rotates cookie") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, refreshToken) = login()

                val response = client.post("/api/auth/change-password") {
                    bearerAuth(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(ChangePasswordRequest("hunter2", "newpwd123"))
                }

                response.status shouldBe HttpStatusCode.NoContent
                val newRefreshCookie = response.refreshTokenCookie()
                newRefreshCookie shouldNotBe null
                newRefreshCookie shouldNotBe refreshToken
            }
        }

        test("change-password invalidates old refresh tokens from other sessions") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (originalAccessToken, originalRefreshToken) = login()

                // second session, used to prove revocation of other sessions
                login()

                client.post("/api/auth/change-password") {
                    bearerAuth(originalAccessToken)
                    contentType(ContentType.Application.Json)
                    setBody(ChangePasswordRequest("hunter2", "newpwd456"))
                }.status shouldBe HttpStatusCode.NoContent

                client.post("/api/auth/refresh") { cookie("refresh_token", originalRefreshToken) }
                    .status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("change-password with wrong old password returns 400") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, _) = login()

                val response = client.post("/api/auth/change-password") {
                    bearerAuth(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(ChangePasswordRequest("wrong-old-pw", "newpwd123"))
                }

                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("change-password without JWT returns 401") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                client.post("/api/auth/change-password") {
                    contentType(ContentType.Application.Json)
                    setBody(ChangePasswordRequest("hunter2", "newpwd123"))
                }.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("login with old password fails after change-password, new password works") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, _) = login()

                client.post("/api/auth/change-password") {
                    bearerAuth(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(ChangePasswordRequest("hunter2", "newpwd789"))
                }.status shouldBe HttpStatusCode.NoContent

                client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice@example.com", "hunter2"))
                }.status shouldBe HttpStatusCode.Unauthorized

                val newLogin = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice@example.com", "newpwd789"))
                }

                newLogin.status shouldBe HttpStatusCode.OK
                newLogin.body<LoginResponse>().accessToken shouldNotBe null
            }
        }

        test("login returns must_change_password only for users with the flag set") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()
                createUser(username = "bob", email = "bob@example.com", mustChangePassword = true)

                val normalLogin = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice@example.com", "hunter2"))
                }
                normalLogin.status shouldBe HttpStatusCode.OK
                normalLogin.body<LoginResponse>().mustChangePassword shouldBe false

                val forcedLogin = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("bob@example.com", "hunter2"))
                }
                forcedLogin.status shouldBe HttpStatusCode.OK
                forcedLogin.body<LoginResponse>().mustChangePassword shouldBe true
            }
        }

        test("login with must_change_password set requires no old password to change it") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser(mustChangePassword = true)

                val (accessToken, _) = login()

                val response = client.post("/api/auth/change-password") {
                    bearerAuth(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(ChangePasswordRequest("", "brandnewpw1"))
                }

                response.status shouldBe HttpStatusCode.NoContent
            }
        }

        test("change-password clears must_change_password flag") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser(mustChangePassword = true)

                val (accessToken, _) = login()

                client.post("/api/auth/change-password") {
                    bearerAuth(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(ChangePasswordRequest("", "brandnewpw1"))
                }.status shouldBe HttpStatusCode.NoContent

                val newLogin = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice@example.com", "brandnewpw1"))
                }
                newLogin.status shouldBe HttpStatusCode.OK
                newLogin.body<LoginResponse>().mustChangePassword shouldBe false
            }
        }

        test("me returns must_change_password for users with the flag set") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser(mustChangePassword = true)

                val (accessToken, _) = login()

                val me = client.get("/api/auth/me") { bearerAuth(accessToken) }
                    .body<MeResponse>()

                me.mustChangePassword shouldBe true
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

        test("me returns per-server permissions for server-scoped group assignment") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                val userId = createUser()
                val nodeId = createNode()
                val serverId = createServer(nodeId, null)
                assignScopedGroup(userId, "Operator", "SERVER", serverId)

                val (accessToken, _) = login()

                val me = client.get("/api/auth/me") { bearerAuth(accessToken) }
                    .body<MeResponse>()

                me.permissions shouldBe emptyList()
                me.serverPermissions.getValue(serverId.toString()) shouldContain "server.restart"
                me.serverPermissions.getValue(serverId.toString()) shouldContain "server.view"
            }
        }

        test("me returns per-server permissions for network-scoped group assignment") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                val userId = createUser()
                val nodeId = createNode()
                val networkId = createNetwork()
                val serverId = createServer(nodeId, networkId)
                assignScopedGroup(userId, "Operator", "NETWORK", networkId)

                val (accessToken, _) = login()

                val me = client.get("/api/auth/me") { bearerAuth(accessToken) }
                    .body<MeResponse>()

                me.permissions shouldBe emptyList()
                me.serverPermissions.getValue(serverId.toString()) shouldContain "server.restart"
                me.serverPermissions.getValue(serverId.toString()) shouldContain "server.view"
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

        // -------------------------------------------------------------------------
        // TOTP
        // -------------------------------------------------------------------------

        fun totpCode(secret: String): String {
            val generator = dev.samstevens.totp.code.DefaultCodeGenerator()
            val timeIndex = System.currentTimeMillis() / 1000L / 30L
            return generator.generate(secret, timeIndex)
        }

        suspend fun io.ktor.client.HttpClient.setupTotp(accessToken: String): TotpSetupResponse {
            val setup = post("/api/auth/totp/setup") { bearerAuth(accessToken) }
                .body<TotpSetupResponse>()
            setup.secret.isNotBlank() shouldBe true
            setup.qrDataUri.startsWith("data:image/png;base64,") shouldBe true
            setup.recoveryCodes.size shouldBe 10
            setup.recoveryCodes.forEach { it.length shouldBe 8 }

            val enable = post("/api/auth/totp/enable") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(TotpEnableRequest(totpCode(setup.secret)))
            }
            enable.status shouldBe HttpStatusCode.NoContent
            return setup
        }

        test("login returns requires_totp and temp_token when TOTP is enabled") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, _) = login()
                client.setupTotp(accessToken)

                val response = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice@example.com", "hunter2"))
                }

                response.status shouldBe HttpStatusCode.OK
                val body = response.body<LoginResponse>()
                body.requiresTotp shouldBe true
                body.tempToken shouldNotBe null
                body.accessToken shouldBe null
                body.expiresIn shouldBe JwtManager.tempTokenTtlSeconds
                response.refreshTokenCookie() shouldBe null
            }
        }

        test("totp-verify with valid code issues access token and refresh cookie") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, _) = login()
                val setup = client.setupTotp(accessToken)

                val challenge = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice@example.com", "hunter2"))
                }
                    .body<LoginResponse>()

                val verify = client.post("/api/auth/totp-verify") {
                    contentType(ContentType.Application.Json)
                    setBody(TotpVerifyRequest(challenge.tempToken!!, totpCode(setup.secret)))
                }

                verify.status shouldBe HttpStatusCode.OK
                val verified = verify.body<LoginResponse>()
                verified.accessToken shouldNotBe null
                verified.requiresTotp shouldBe false
                verify.refreshTokenCookie() shouldNotBe null
            }
        }

        test("totp-verify with wrong code returns 401") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, _) = login()
                client.setupTotp(accessToken)

                val challenge = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice@example.com", "hunter2"))
                }
                    .body<LoginResponse>()

                val wrongCode = totpCode("Z".repeat(16))
                val verify = client.post("/api/auth/totp-verify") {
                    contentType(ContentType.Application.Json)
                    setBody(TotpVerifyRequest(challenge.tempToken!!, wrongCode))
                }
                verify.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("totp-verify with garbage temp token returns 401") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()

                client.post("/api/auth/totp-verify") {
                    contentType(ContentType.Application.Json)
                    setBody(TotpVerifyRequest("not-a-token", "123456"))
                }.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("totp-verify when TOTP is not enabled returns 400") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                val userId = createUser()

                val tempToken = jwtManager.generateTotpTempToken(userId)
                client.post("/api/auth/totp-verify") {
                    contentType(ContentType.Application.Json)
                    setBody(TotpVerifyRequest(tempToken, "123456"))
                }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("recovery code logs in exactly once") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, _) = login()
                val setup = client.setupTotp(accessToken)
                val recoveryCode = setup.recoveryCodes.first()

                val challenge = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice@example.com", "hunter2"))
                }
                    .body<LoginResponse>()

                val first = client.post("/api/auth/totp-recovery") {
                    contentType(ContentType.Application.Json)
                    setBody(TotpRecoveryRequest(challenge.tempToken!!, recoveryCode))
                }
                first.status shouldBe HttpStatusCode.OK
                first.body<LoginResponse>().accessToken shouldNotBe null

                val status = client.get("/api/auth/totp/status") { bearerAuth(accessToken) }
                    .body<TotpStatusResponse>()
                status.recoveryCodesRemaining shouldBe 9

                val replay = client.post("/api/auth/totp-recovery") {
                    contentType(ContentType.Application.Json)
                    setBody(TotpRecoveryRequest(challenge.tempToken!!, recoveryCode))
                }
                replay.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("recovery code with garbage temp token returns 401") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()

                client.post("/api/auth/totp-recovery") {
                    contentType(ContentType.Application.Json)
                    setBody(TotpRecoveryRequest("not-a-token", "12345678"))
                }.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("enable with wrong code returns 400 and does not enable TOTP") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, _) = login()
                client.post("/api/auth/totp/setup") { bearerAuth(accessToken) }
                    .status shouldBe HttpStatusCode.OK

                val enable = client.post("/api/auth/totp/enable") {
                    bearerAuth(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(TotpEnableRequest("000000"))
                }
                enable.status shouldBe HttpStatusCode.BadRequest

                client.get("/api/auth/totp/status") { bearerAuth(accessToken) }
                    .body<TotpStatusResponse>().enabled shouldBe false
            }
        }

        test("enable without setup returns 400") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, _) = login()

                client.post("/api/auth/totp/enable") {
                    bearerAuth(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(TotpEnableRequest(totpCode("A".repeat(16))))
                }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("setup when already enabled returns 409") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, _) = login()
                client.setupTotp(accessToken)

                client.post("/api/auth/totp/setup") { bearerAuth(accessToken) }
                    .status shouldBe HttpStatusCode.Conflict
            }
        }

        test("enable when already enabled returns 409") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, _) = login()
                client.setupTotp(accessToken)

                client.post("/api/auth/totp/enable") {
                    bearerAuth(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(TotpEnableRequest("123456"))
                }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("disable disables TOTP and clears recovery codes") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, _) = login()
                val setup = client.setupTotp(accessToken)

                val disable = client.post("/api/auth/totp/disable") {
                    bearerAuth(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(TotpDisableRequest(totpCode(setup.secret)))
                }
                disable.status shouldBe HttpStatusCode.NoContent

                val status = client.get("/api/auth/totp/status") { bearerAuth(accessToken) }
                    .body<TotpStatusResponse>()
                status.enabled shouldBe false
                status.recoveryCodesRemaining shouldBe 0

                // login reverts to the direct access-token flow
                val loginResponse = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice@example.com", "hunter2"))
                }
                loginResponse.status shouldBe HttpStatusCode.OK
                loginResponse.body<LoginResponse>().accessToken shouldNotBe null
            }
        }

        test("disable with wrong code returns 400 and keeps TOTP enabled") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, _) = login()
                client.setupTotp(accessToken)

                client.post("/api/auth/totp/disable") {
                    bearerAuth(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(TotpDisableRequest("000000"))
                }.status shouldBe HttpStatusCode.BadRequest

                client.get("/api/auth/totp/status") { bearerAuth(accessToken) }
                    .body<TotpStatusResponse>().enabled shouldBe true
            }
        }

        test("disable when TOTP is not enabled returns 400") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, _) = login()

                client.post("/api/auth/totp/disable") {
                    bearerAuth(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(TotpDisableRequest("123456"))
                }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("me reports totp_enabled") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()

                val (accessToken, _) = login()

                client.get("/api/auth/me") { bearerAuth(accessToken) }
                    .body<MeResponse>().totpEnabled shouldBe false

                client.setupTotp(accessToken)

                client.get("/api/auth/me") { bearerAuth(accessToken) }
                    .body<MeResponse>().totpEnabled shouldBe true
            }
        }

        test("trusted device skips TOTP challenge, and survives logout") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()
                val (accessToken, _) = login()
                val setup = client.setupTotp(accessToken)
                val fp = "test-device-fingerprint"
                val userAgent = "TestAgent/1.0"

                // Step 1 — password login triggers TOTP challenge
                val challenge = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice@example.com", "hunter2", deviceFingerprint = fp))
                }.body<LoginResponse>()
                challenge.requiresTotp shouldBe true
                challenge.tempToken shouldNotBe null

                // Step 2 — verify TOTP with trust_device=true and fingerprint
                val verify = client.post("/api/auth/totp-verify") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.UserAgent, userAgent)
                    setBody(TotpVerifyRequest(challenge.tempToken!!, totpCode(setup.secret), trustDevice = true, deviceFingerprint = fp))
                }
                verify.status shouldBe HttpStatusCode.OK
                val verifyBody = verify.body<LoginResponse>()
                verifyBody.accessToken shouldNotBe null
                val trustedToken = verify.refreshTokenCookie()!!
                trustedToken shouldNotBe null
                val deviceTrustCookie = verify.headers.getAll(HttpHeaders.SetCookie)
                    ?.find { it.startsWith("device_trust=") }
                    ?.split(";")
                    ?.first()
                    ?.removePrefix("device_trust=")
                    ?.takeIf { it.isNotEmpty() }
                deviceTrustCookie shouldNotBe null

                // Step 3 — logout (device_trust cookie should NOT be cleared by logout)
                client.post("/api/auth/logout") {
                    bearerAuth(verifyBody.accessToken!!)
                    cookie("refresh_token", trustedToken)
                }.status shouldBe HttpStatusCode.NoContent

                // Step 4 — login again from the same device, should skip TOTP
                val relogin = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.UserAgent, userAgent)
                    cookie("device_trust", deviceTrustCookie!!)
                    setBody(LoginRequest("alice@example.com", "hunter2", deviceFingerprint = fp))
                }
                relogin.status shouldBe HttpStatusCode.OK
                val reloginBody = relogin.body<LoginResponse>()
                reloginBody.requiresTotp shouldBe false
                reloginBody.accessToken shouldNotBe null
                relogin.refreshTokenCookie() shouldNotBe null
            }
        }

        test("trusted device skips TOTP only when fingerprint matches") {
            testApplication {
                application { configureTest() }
                val client = jsonClient()
                createUser()
                val (accessToken, _) = login()
                val setup = client.setupTotp(accessToken)
                val fp1 = "device-fingerprint-1"
                val fp2 = "device-fingerprint-2"
                val userAgent = "TestAgent/1.0"

                // Login with TOTP + trust device (fp1)
                val challenge = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest("alice@example.com", "hunter2", deviceFingerprint = fp1))
                }.body<LoginResponse>()
                challenge.requiresTotp shouldBe true

                val verify = client.post("/api/auth/totp-verify") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.UserAgent, userAgent)
                    setBody(TotpVerifyRequest(challenge.tempToken!!, totpCode(setup.secret), trustDevice = true, deviceFingerprint = fp1))
                }
                verify.status shouldBe HttpStatusCode.OK
                val deviceTrustCookie = verify.headers.getAll(HttpHeaders.SetCookie)
                    ?.find { it.startsWith("device_trust=") }
                    ?.split(";")
                    ?.first()
                    ?.removePrefix("device_trust=")
                    ?.takeIf { it.isNotEmpty() }
                deviceTrustCookie shouldNotBe null

                // Logout
                client.post("/api/auth/logout") {
                    bearerAuth(verify.body<LoginResponse>().accessToken!!)
                    cookie("refresh_token", verify.refreshTokenCookie()!!)
                }.status shouldBe HttpStatusCode.NoContent

                // Login with same cookie but DIFFERENT fingerprint — should NOT skip
                val relogin = client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.UserAgent, userAgent)
                    cookie("device_trust", deviceTrustCookie!!)
                    setBody(LoginRequest("alice@example.com", "hunter2", deviceFingerprint = fp2))
                }
                relogin.status shouldBe HttpStatusCode.OK
                val reloginBody = relogin.body<LoginResponse>()
                reloginBody.requiresTotp shouldBe true
                reloginBody.tempToken shouldNotBe null
                reloginBody.accessToken shouldBe null
            }
        }
    })
