package io.craftpanel.master.routes

import com.craftpanel.agent.v1.MasterMessage
import io.craftpanel.master.TestDatabase
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
import io.ktor.server.websocket.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import io.craftpanel.master.service.ForbiddenException as ServiceForbiddenException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import kotlinx.serialization.json.Json

class MigrationsRoutesTest {

    private val jwtConfig = JwtConfig(
        secret = "test-secret-that-is-at-least-32-characters!!",
        issuer = "craftpanel-test",
        audience = "craftpanel-test",
        expirySeconds = 900,
    )
    private val jwtManager = JwtManager(jwtConfig)
    private val noopSend: (String, MasterMessage) -> Boolean = { _, _ -> true }
    private val testScope = TestScope()

    private fun buildMigrationService(): MigrationService = MigrationService(
        sendToNode = noopSend,
        rsyncReadyFlow = MutableSharedFlow(),
        rsyncProgressFlow = MutableSharedFlow(),
        rsyncCompleteFlow = MutableSharedFlow(),
        serverStatusFlow = MutableSharedFlow(),
        dnsProvider = null,
        scope = testScope,
    )

    @BeforeTest
    fun setup() {
        TestDatabase.initIfNeeded()
        TestDatabase.reset()
    }

    private fun Application.configureTest(svc: MigrationService = buildMigrationService()) {
        install(WebSockets)
        install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(StatusPages) {
            exception<NotFoundException> { call, ex ->
                call.respond(HttpStatusCode.NotFound, mapOf("error" to (ex.message ?: "Not found")))
            }
            exception<ServiceForbiddenException> { call, ex ->
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to (ex.message ?: "Forbidden")))
            }
            exception<ConflictException> { call, ex ->
                call.respond(HttpStatusCode.Conflict, mapOf("error" to (ex.message ?: "Conflict")))
            }
            exception<UnprocessableException> { call, ex ->
                call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to (ex.message ?: "Unprocessable")))
            }
            exception<BadRequestException> { call, ex ->
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (ex.message ?: "Bad request")))
            }
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
        routing { migrationsRoutes(svc) }
    }

    private fun createSuperAdminJwt(): String {
        val userId = transaction {
            Users.insert {
                it[Users.username] = "admin"
                it[Users.email] = "admin@test.com"
                it[Users.passwordHash] = "hash"
                it[Users.isActive] = true
            }[Users.id].let { UUID.fromString(it.toString()) }
        }
        transaction {
            val groupId = Groups.selectAll()
                .where { Groups.name eq "Super Admin" }
                .first()[Groups.id]
            UserGroupAssignments.insert {
                it[UserGroupAssignments.userId] = userId.toKotlinUuid()
                it[UserGroupAssignments.groupId] = groupId
                it[UserGroupAssignments.scopeType] = "GLOBAL"
            }
        }
        return jwtManager.generate(TokenClaims(userId = userId, name = "Admin", email = "admin@test.com", groups = listOf("Super Admin")))
    }

    private fun insertNode(status: String = "ACTIVE"): Pair<UUID, kotlin.uuid.Uuid> {
        val nodeId = transaction {
            Nodes.insert {
                it[Nodes.displayName] = "Test Node"
                it[Nodes.hostname] = "node1.test"
                it[Nodes.publicIp] = "1.2.3.4"
                it[Nodes.privateIp] = "10.0.0.1"
                it[Nodes.tokenHash] = UUID.randomUUID().toString()
                it[Nodes.status] = status
                it[Nodes.dataPath] = "/data"
            }[Nodes.id]
        }
        return UUID.fromString(nodeId.toString()) to nodeId
    }

    private fun insertServer(nodeId: kotlin.uuid.Uuid): Pair<UUID, kotlin.uuid.Uuid> {
        val serverId = transaction {
            Servers.insert {
                it[Servers.name] = "test-server-${UUID.randomUUID()}"
                it[Servers.displayName] = "Test Server"
                it[Servers.nodeId] = nodeId
                it[Servers.hostPort] = 25565
                it[Servers.memoryMb] = 1024
                it[Servers.status] = "HEALTHY"
            }[Servers.id]
        }
        return UUID.fromString(serverId.toString()) to serverId
    }

    @Test
    fun `list migrations returns empty for new server`() = testApplication {
        val (_, sourceNodeKId) = insertNode()
        val (serverJavaId, _) = insertServer(sourceNodeKId)
        val token = createSuperAdminJwt()
        application { configureTest() }
        val client = createClient { install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val response = client.get("/api/servers/$serverJavaId/migrations") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<JsonObject>()
        assertEquals(0, body["migrations"]!!.jsonArray.size)
    }

    @Test
    fun `start migration returns 404 when server not found`() = testApplication {
        val token = createSuperAdminJwt()
        application { configureTest() }
        val client = createClient { install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val response = client.post("/api/servers/${UUID.randomUUID()}/migrations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"target_node_id":"${UUID.randomUUID()}"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `start migration returns 409 when source and target are same node`() = testApplication {
        val (nodeJavaId, nodeKId) = insertNode()
        val (serverJavaId, _) = insertServer(nodeKId)
        val token = createSuperAdminJwt()
        application { configureTest() }
        val client = createClient { install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val response = client.post("/api/servers/$serverJavaId/migrations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"target_node_id":"$nodeJavaId"}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `start migration returns 404 when target node not found`() = testApplication {
        val (_, sourceKId) = insertNode()
        val (serverJavaId, _) = insertServer(sourceKId)
        val token = createSuperAdminJwt()
        application { configureTest() }
        val client = createClient { install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val response = client.post("/api/servers/$serverJavaId/migrations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"target_node_id":"${UUID.randomUUID()}"}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `start migration returns 409 when target node is not ACTIVE`() = testApplication {
        val (_, sourceKId) = insertNode()
        val (targetJavaId, _) = insertNode(status = "PENDING")
        val (serverJavaId, _) = insertServer(sourceKId)
        val token = createSuperAdminJwt()
        application { configureTest() }
        val client = createClient { install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val response = client.post("/api/servers/$serverJavaId/migrations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"target_node_id":"$targetJavaId"}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `start migration returns 202 and creates pending migration`() = testApplication {
        val (_, sourceKId) = insertNode()
        val (targetJavaId, targetKId) = insertNode()
        transaction {
            PortRegistry.insert {
                it[PortRegistry.nodeId] = targetKId
                it[PortRegistry.port] = 25570
                it[PortRegistry.protocol] = "TCP"
                it[PortRegistry.serverId] = null
            }
        }
        val (serverJavaId, _) = insertServer(sourceKId)
        val token = createSuperAdminJwt()
        application { configureTest() }
        val client = createClient { install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val response = client.post("/api/servers/$serverJavaId/migrations") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"target_node_id":"$targetJavaId"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = response.body<JsonObject>()
        assertNotNull(body["id"])
        assertEquals("PENDING", body["status"]!!.jsonPrimitive.content)
        assertEquals(serverJavaId.toString(), body["server_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get migration returns 404 for unknown id`() = testApplication {
        val token = createSuperAdminJwt()
        application { configureTest() }
        val client = createClient { install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val response = client.get("/api/migrations/${UUID.randomUUID()}") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
