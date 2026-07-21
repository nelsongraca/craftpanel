package io.craftpanel.master.routes

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.auth.JWT_AUTH
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.TokenClaims
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.service.BadRequestException
import io.craftpanel.master.service.ConflictException
import io.craftpanel.master.service.EnvVarsService
import io.craftpanel.master.service.ForbiddenException
import io.craftpanel.master.service.NotFoundException
import io.craftpanel.master.service.ProxyBackendService
import io.craftpanel.master.service.ProxySettingsResponse
import io.craftpanel.master.service.ProxySettingsService
import io.craftpanel.master.service.UnprocessableException
import io.craftpanel.master.service.UpdateProxySettingsRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ConfigRoutesTest :
    FunSpec({
        val repos = TestRepositories()
        val proxyBackendService = ProxyBackendService(repos.serverRepository, repos.proxyBackendRepository)
        val envVarsService = EnvVarsService(repos.serverRepository, repos.envVarsRepository)
        val proxySettingsService = ProxySettingsService(repos.serverRepository)

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

        fun Application.configureTest() {
            install(StatusPages) {
                exception<NotFoundException> { call, ex -> call.respond(HttpStatusCode.NotFound, ex.message ?: "Not found") }
                exception<ForbiddenException> { call, ex -> call.respond(HttpStatusCode.Forbidden, ex.message ?: "Forbidden") }
                exception<ConflictException> { call, ex -> call.respond(HttpStatusCode.Conflict, ex.message ?: "Conflict") }
                exception<UnprocessableException> { call, ex -> call.respond(HttpStatusCode.UnprocessableEntity, ex.message ?: "Unprocessable") }
                exception<BadRequestException> { call, ex -> call.respond(HttpStatusCode.BadRequest, ex.message ?: "Bad request") }
            }
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(Authentication) {
                jwt(JWT_AUTH) {
                    verifier(jwtManager.verifier)
                    validate { credential ->
                        if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null
                    }
                }
            }
            routing { configRoutes(proxyBackendService, envVarsService, proxySettingsService) }
        }

        fun ApplicationTestBuilder.jsonClient() = createClient {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        fun createUser(): Uuid = transaction {
            Users.insert {
                it[Users.username] = "u"
                it[Users.email] = "u@example.com"
                it[Users.passwordHash] = "hash"
                it[Users.isActive] = true
            }[Users.id].let { Uuid.parse(it.toString()) }
        }

        fun assignAdmin(userId: Uuid) = transaction {
            val groupId = Groups.selectAll().where { Groups.name eq "Server Admin" }.first()[Groups.id]
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

        fun createServer(nodeId: Uuid, type: String): Uuid = transaction {
            Servers.insert {
                it[Servers.nodeId] = nodeId
                it[Servers.networkId] = null
                it[Servers.name] = "srv"
                it[Servers.displayName] = "srv"
                it[Servers.serverType] = type
                it[Servers.mcVersion] = "1.21.4"
                it[Servers.hostPort] = 25565
                it[Servers.memoryMb] = 1024
                it[Servers.cpuShares] = 0
                it[Servers.status] = "STOPPED"
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }

        fun tokenFor(userId: Uuid): String = jwtManager.generate(TokenClaims(userId = userId, name = "u", email = "u@example.com", groups = emptyList()))

        test("getProxySettings returns 404 for unknown server") {
            testApplication {
                application { configureTest() }
                val userId = createUser()
                assignAdmin(userId)
                val resp = jsonClient().get("/api/servers/${Uuid.random()}/config/proxy-settings") { bearerAuth(tokenFor(userId)) }
                resp.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("updateProxySettings persists and is returned by GET") {
            testApplication {
                application { configureTest() }
                val userId = createUser()
                assignAdmin(userId)
                val nodeId = createNode()
                val proxyId = createServer(nodeId, "VELOCITY")

                val putResp = jsonClient().put("/api/servers/$proxyId/config/proxy-settings") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody(UpdateProxySettingsRequest(motd = "Welcome", maxPlayers = 40, forwardingMode = "legacy"))
                }
                putResp.status shouldBe HttpStatusCode.OK
                val saved = putResp.body<ProxySettingsResponse>()
                saved.motd shouldBe "Welcome"
                saved.maxPlayers shouldBe 40
                saved.forwardingMode shouldBe "LEGACY"

                val getResp = jsonClient().get("/api/servers/$proxyId/config/proxy-settings") { bearerAuth(tokenFor(userId)) }
                getResp.status shouldBe HttpStatusCode.OK
                val fetched = getResp.body<ProxySettingsResponse>()
                fetched.motd shouldBe "Welcome"
                fetched.maxPlayers shouldBe 40
                fetched.forwardingMode shouldBe "LEGACY"
            }
        }

        test("updateProxySettings rejects a non-proxy server with 409") {
            testApplication {
                application { configureTest() }
                val userId = createUser()
                assignAdmin(userId)
                val nodeId = createNode()
                val vanillaId = createServer(nodeId, "VANILLA")

                val resp = jsonClient().put("/api/servers/$vanillaId/config/proxy-settings") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody(UpdateProxySettingsRequest(motd = "x", maxPlayers = 10, forwardingMode = "legacy"))
                }
                resp.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("updateProxySettings rejects an invalid forwarding mode with 422") {
            testApplication {
                application { configureTest() }
                val userId = createUser()
                assignAdmin(userId)
                val nodeId = createNode()
                val proxyId = createServer(nodeId, "VELOCITY")

                val resp = jsonClient().put("/api/servers/$proxyId/config/proxy-settings") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody(UpdateProxySettingsRequest(motd = null, maxPlayers = null, forwardingMode = "bogus"))
                }
                resp.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        test("updateProxySettings rejects non-positive maxPlayers with 422") {
            testApplication {
                application { configureTest() }
                val userId = createUser()
                assignAdmin(userId)
                val nodeId = createNode()
                val proxyId = createServer(nodeId, "VELOCITY")

                val resp = jsonClient().put("/api/servers/$proxyId/config/proxy-settings") {
                    bearerAuth(tokenFor(userId))
                    contentType(ContentType.Application.Json)
                    setBody(UpdateProxySettingsRequest(motd = null, maxPlayers = 0, forwardingMode = null))
                }
                resp.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }
    })
