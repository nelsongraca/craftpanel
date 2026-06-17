package io.craftpanel.master.auth

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.service.BadRequestException
import io.craftpanel.master.service.ConflictException
import io.craftpanel.master.service.ForbiddenException
import io.craftpanel.master.service.NotFoundException
import io.craftpanel.master.service.UnprocessableException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class RouteAuthorizationTest : FunSpec({
    val jwtConfig = JwtConfig(
        secret = "test-secret-that-is-at-least-32-characters!!",
        issuer = "craftpanel-test",
        audience = "craftpanel-test",
        expirySeconds = 900,
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
        install(Authentication) {
            jwt(JWT_AUTH) {
                verifier(jwtManager.verifier)
                validate { credential ->
                    if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null
                }
            }
        }
        routing {
            authenticate(JWT_AUTH) {
                get("/probe/{id}") {
                    val auth = call.requireServerPermission(Permission.SERVER_VIEW)
                    call.respond("${auth.serverId}|${auth.networkId}")
                }
                get("/probe-global") {
                    call.requirePermission(Permission.SYSTEM_NODES)
                    call.respond("ok")
                }
            }
        }
    }

    fun createUser(): Uuid = transaction {
        Users.insert {
            it[Users.username] = "u"
            it[Users.email] = "u@example.com"
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
            it[ServerNetworks.type] = "VANILLA"
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

    fun tokenFor(userId: Uuid): String =
        jwtManager.generate(TokenClaims(userId = userId, name = "u", email = "u@example.com", groups = emptyList()))

    test("requireServerPermission returns 400 for malformed server id") {
        testApplication {
            application { configureTest() }
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val resp = client.get("/probe/not-a-uuid") { bearerAuth(tokenFor(userId)) }
            resp.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("requireServerPermission returns 404 for unknown server") {
        testApplication {
            application { configureTest() }
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val resp = client.get("/probe/${Uuid.random()}") { bearerAuth(tokenFor(userId)) }
            resp.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("requireServerPermission returns 403 when permission missing") {
        testApplication {
            application { configureTest() }
            val userId = createUser()
            val nodeId = createNode()
            val serverId = createServer(nodeId, networkId = null)
            val resp = client.get("/probe/$serverId") { bearerAuth(tokenFor(userId)) }
            resp.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("requireServerPermission succeeds and exposes server and network ids") {
        testApplication {
            application { configureTest() }
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val nodeId = createNode()
            val networkId = createNetwork()
            val serverId = createServer(nodeId, networkId = networkId)
            val resp = client.get("/probe/$serverId") { bearerAuth(tokenFor(userId)) }
            resp.status shouldBe HttpStatusCode.OK
        }
    }

    test("requirePermission returns 403 when global permission missing") {
        testApplication {
            application { configureTest() }
            val userId = createUser()
            val resp = client.get("/probe-global") { bearerAuth(tokenFor(userId)) }
            resp.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("requirePermission succeeds for permitted global action") {
        testApplication {
            application { configureTest() }
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val resp = client.get("/probe-global") { bearerAuth(tokenFor(userId)) }
            resp.status shouldBe HttpStatusCode.OK
        }
    }
})
