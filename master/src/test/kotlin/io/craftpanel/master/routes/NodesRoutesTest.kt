package io.craftpanel.master.routes

import com.craftpanel.agent.v1.MasterMessage
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.*
import kotlin.test.*
import kotlin.time.Clock
import io.craftpanel.master.service.ForbiddenException as ServiceForbiddenException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class NodesRoutesTest {

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

    private fun Application.configureTest(sendToNode: (String, MasterMessage) -> Boolean = { _, _ -> false }) {
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
        routing { nodesRoutes(NodeService(sendToNode)) }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun createUser(
        username: String = "admin",
        email: String = "admin@example.com",
        password: String = "hunter2",
    ): UUID = transaction {
        Users.insert {
            it[Users.username] = username
            it[Users.email] = email
            it[Users.passwordHash] = Argon2Hasher.hash(password)
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

    private fun tokenFor(userId: UUID, username: String = "admin"): String =
        jwtManager.generate(TokenClaims(userId = userId, name = username, email = "$username@example.com", groups = emptyList()))

    private fun createNode(
        hostname: String = "node-1",
        status: String = "PENDING",
        totalRamMb: Int = 8192,
        totalCpuShares: Int = 1024,
        tokenHash: String = "aabbcc${
            hostname.hashCode()
                .toString(16)
                .padStart(58, '0')
        }",
    ): UUID = transaction {
        Nodes.insert {
            it[Nodes.hostname] = hostname
            it[Nodes.displayName] = hostname
            it[Nodes.publicIp] = "1.2.3.4"
            it[Nodes.privateIp] = "10.0.0.1"
            it[Nodes.tokenHash] = tokenHash.take(64)
                .padEnd(64, '0')
            it[Nodes.status] = status
            it[Nodes.totalRamMb] = totalRamMb
            it[Nodes.totalCpuShares] = totalCpuShares
        }[Nodes.id].let { UUID.fromString(it.toString()) }
    }

    private fun createServer(nodeId: UUID, memoryMb: Int = 1024, cpuShares: Int = 256): UUID = transaction {
        Servers.insert {
            it[Servers.nodeId] = nodeId.toKotlinUuid()
            it[Servers.name] = "server-${UUID.randomUUID()}"
            it[Servers.hostPort] = 25565
            it[Servers.memoryMb] = memoryMb
            it[Servers.cpuShares] = cpuShares
        }[Servers.id].let { UUID.fromString(it.toString()) }
    }

    private fun createNodeMetric(nodeId: UUID, cpuPercent: Double = 42.0, ramUsedMb: Int = 2048) = transaction {
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC)
        NodeMetrics.insert {
            it[NodeMetrics.nodeId] = nodeId.toKotlinUuid()
            it[NodeMetrics.recordedAt] = now
            it[NodeMetrics.cpuPercent] = cpuPercent
            it[NodeMetrics.ramUsedMb] = ramUsedMb
            it[NodeMetrics.ramTotalMb] = 8192
            it[NodeMetrics.netInBytes] = 1024L
            it[NodeMetrics.netOutBytes] = 512L
            it[NodeMetrics.diskUsedBytes] = 10_000_000L
            it[NodeMetrics.diskTotalBytes] = 50_000_000L
        }
    }

    // -------------------------------------------------------------------------
    // authentication / authorization
    // -------------------------------------------------------------------------

    @Test
    fun `GET nodes without JWT returns 401`() = testApplication {
        application { configureTest() }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/nodes").status)
    }

    @Test
    fun `GET nodes without system_nodes permission returns 403`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Viewer")

        val response = client.get("/api/nodes") { bearerAuth(tokenFor(userId)) }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET nodes with system_nodes permission returns 200`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")

        assertEquals(HttpStatusCode.OK, client.get("/api/nodes") { bearerAuth(tokenFor(userId)) }.status)
    }

    // -------------------------------------------------------------------------
    // GET /nodes
    // -------------------------------------------------------------------------

    @Test
    fun `GET nodes returns empty list when no nodes exist`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val client = jsonClient()

        val body = client.get("/api/nodes") { bearerAuth(tokenFor(userId)) }
            .body<List<JsonObject>>()

        assertTrue(body.isEmpty())
    }

    @Test
    fun `GET nodes returns node with snake_case fields`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        createNode(hostname = "alpha")
        val client = jsonClient()

        val body = client.get("/api/nodes") { bearerAuth(tokenFor(userId)) }
            .body<List<JsonObject>>()

        assertEquals(1, body.size)
        val node = body[0]
        assertNotNull(node["id"])
        assertNotNull(node["display_name"])
        assertNotNull(node["public_ip"])
        assertNotNull(node["private_ip"])
        assertNotNull(node["total_ram_mb"])
        assertNotNull(node["total_cpu_shares"])
        assertNotNull(node["allocated_ram_mb"])
        assertNotNull(node["allocated_cpu_shares"])
        assertNotNull(node["port_range_start"])
        assertNotNull(node["port_range_end"])
        assertNotNull(node["data_path"])
        assertNotNull(node["created_at"])
        assertEquals("alpha", node["display_name"]!!.jsonPrimitive.content)
        assertEquals("PENDING", node["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET nodes computes allocated_ram_mb from servers on that node`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        createServer(nodeId, memoryMb = 1024)
        createServer(nodeId, memoryMb = 2048)
        val client = jsonClient()

        val body = client.get("/api/nodes") { bearerAuth(tokenFor(userId)) }
            .body<List<JsonObject>>()

        assertEquals(1, body.size)
        assertEquals(3072, body[0]["allocated_ram_mb"]!!.jsonPrimitive.content.toInt())
        assertEquals(512, body[0]["allocated_cpu_shares"]!!.jsonPrimitive.content.toInt())
    }

    // -------------------------------------------------------------------------
    // GET /nodes/{id}
    // -------------------------------------------------------------------------

    @Test
    fun `GET node by id returns 404 for unknown node`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")

        val response = client.get("/api/nodes/${UUID.randomUUID()}") { bearerAuth(tokenFor(userId)) }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET node by id returns 400 for invalid UUID`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")

        val response = client.get("/api/nodes/not-a-uuid") { bearerAuth(tokenFor(userId)) }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET node by id returns correct node`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode(hostname = "beta", totalRamMb = 4096)
        val client = jsonClient()

        val body = client.get("/api/nodes/$nodeId") { bearerAuth(tokenFor(userId)) }
            .body<JsonObject>()

        assertEquals(nodeId.toString(), body["id"]!!.jsonPrimitive.content)
        assertEquals("beta", body["display_name"]!!.jsonPrimitive.content)
        assertEquals(4096, body["total_ram_mb"]!!.jsonPrimitive.content.toInt())
    }

    // -------------------------------------------------------------------------
    // POST /nodes/{id}/trust
    // -------------------------------------------------------------------------

    @Test
    fun `trust sets node status to ACTIVE`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode(status = "PENDING")

        val response = client.post("/api/nodes/$nodeId/trust") { bearerAuth(tokenFor(userId)) }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val status = transaction {
            Nodes.selectAll()
                .where { Nodes.id eq nodeId.toKotlinUuid() }
                .first()[Nodes.status]
        }
        assertEquals("ACTIVE", status)
    }

    @Test
    fun `trust returns 404 for unknown node`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")

        assertEquals(HttpStatusCode.NotFound, client.post("/api/nodes/${UUID.randomUUID()}/trust") { bearerAuth(tokenFor(userId)) }.status)
    }

    // -------------------------------------------------------------------------
    // POST /nodes/{id}/reject
    // -------------------------------------------------------------------------

    @Test
    fun `reject sets node status to REJECTED`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode(status = "PENDING")

        val response = client.post("/api/nodes/$nodeId/reject") { bearerAuth(tokenFor(userId)) }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val status = transaction {
            Nodes.selectAll()
                .where { Nodes.id eq nodeId.toKotlinUuid() }
                .first()[Nodes.status]
        }
        assertEquals("REJECTED", status)
    }

    @Test
    fun `reject returns 404 for unknown node`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")

        assertEquals(HttpStatusCode.NotFound, client.post("/api/nodes/${UUID.randomUUID()}/reject") { bearerAuth(tokenFor(userId)) }.status)
    }

    // -------------------------------------------------------------------------
    // POST /nodes/{id}/token/rotate
    // -------------------------------------------------------------------------

    @Test
    fun `token rotate returns new node_key`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val client = jsonClient()

        val response = client.post("/api/nodes/$nodeId/token/rotate") { bearerAuth(tokenFor(userId)) }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<JsonObject>()
        assertNotNull(body["node_key"])
        assertTrue(body["node_key"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `token rotate changes the stored token hash`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val tokenHash = "a".repeat(64)
        val nodeId = createNode(tokenHash = tokenHash)
        val client = jsonClient()

        client.post("/api/nodes/$nodeId/token/rotate") { bearerAuth(tokenFor(userId)) }

        val newHash = transaction {
            Nodes.selectAll()
                .where { Nodes.id eq nodeId.toKotlinUuid() }
                .first()[Nodes.tokenHash]
        }
        assertNotEquals(tokenHash, newHash)
    }

    @Test
    fun `token rotate returns 404 for unknown node`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")

        assertEquals(HttpStatusCode.NotFound, client.post("/api/nodes/${UUID.randomUUID()}/token/rotate") { bearerAuth(tokenFor(userId)) }.status)
    }

    // -------------------------------------------------------------------------
    // POST /nodes/{id}/shutdown
    // -------------------------------------------------------------------------

    @Test
    fun `shutdown returns 502 when agent not connected`() = testApplication {
        application { configureTest(sendToNode = { _, _ -> false }) }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()

        assertEquals(HttpStatusCode.BadGateway, client.post("/api/nodes/$nodeId/shutdown") { bearerAuth(tokenFor(userId)) }.status)
    }

    @Test
    fun `shutdown returns 202 when agent is connected`() = testApplication {
        application { configureTest(sendToNode = { _, _ -> true }) }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()

        assertEquals(HttpStatusCode.Accepted, client.post("/api/nodes/$nodeId/shutdown") { bearerAuth(tokenFor(userId)) }.status)
    }

    @Test
    fun `shutdown sends message to the correct node id`() = testApplication {
        var sentTo: String? = null
        application { configureTest(sendToNode = { id, _ -> sentTo = id; true }) }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()

        client.post("/api/nodes/$nodeId/shutdown") { bearerAuth(tokenFor(userId)) }

        assertEquals(nodeId.toString(), sentTo)
    }

    @Test
    fun `shutdown returns 404 for unknown node`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")

        assertEquals(HttpStatusCode.NotFound, client.post("/api/nodes/${UUID.randomUUID()}/shutdown") { bearerAuth(tokenFor(userId)) }.status)
    }

    // -------------------------------------------------------------------------
    // PATCH /nodes/{id}
    // -------------------------------------------------------------------------

    @Test
    fun `patch updates display_name`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode(hostname = "old-name")
        val client = jsonClient()

        val response = client.patch("/api/nodes/$nodeId") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody(PatchNodeRequest(displayName = "new-name"))
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val name = transaction {
            Nodes.selectAll()
                .where { Nodes.id eq nodeId.toKotlinUuid() }
                .first()[Nodes.displayName]
        }
        assertEquals("new-name", name)
    }

    @Test
    fun `patch validates port range start must be less than end`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val client = jsonClient()

        val response = client.patch("/api/nodes/$nodeId") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody(PatchNodeRequest(portRangeStart = 25600, portRangeEnd = 25565))
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    @Test
    fun `patch validates range using current db value for unspecified field`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()  // defaults: start=25570, end=26070
        val client = jsonClient()

        // setting start to 27000 would exceed the current end of 26070 — should fail
        val response = client.patch("/api/nodes/$nodeId") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody(PatchNodeRequest(portRangeStart = 27000))
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    @Test
    fun `patch returns 404 for unknown node`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val client = jsonClient()

        val response = client.patch("/api/nodes/${UUID.randomUUID()}") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody(PatchNodeRequest(displayName = "x"))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // -------------------------------------------------------------------------
    // DELETE /nodes/{id}
    // -------------------------------------------------------------------------

    @Test
    fun `delete sets node status to DECOMMISSIONED`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode(status = "ACTIVE")

        val response = client.delete("/api/nodes/$nodeId") { bearerAuth(tokenFor(userId)) }

        assertEquals(HttpStatusCode.NoContent, response.status)
        val status = transaction {
            Nodes.selectAll()
                .where { Nodes.id eq nodeId.toKotlinUuid() }
                .first()[Nodes.status]
        }
        assertEquals("DECOMMISSIONED", status)
    }

    @Test
    fun `delete returns 404 for unknown node`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")

        assertEquals(HttpStatusCode.NotFound, client.delete("/api/nodes/${UUID.randomUUID()}") { bearerAuth(tokenFor(userId)) }.status)
    }

    // -------------------------------------------------------------------------
    // GET /nodes/{id}/metrics
    // -------------------------------------------------------------------------

    @Test
    fun `metrics returns 404 for unknown node`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")

        assertEquals(HttpStatusCode.NotFound, client.get("/api/nodes/${UUID.randomUUID()}/metrics") { bearerAuth(tokenFor(userId)) }.status)
    }

    @Test
    fun `metrics returns empty columnar arrays when no data`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val client = jsonClient()

        val body = client.get("/api/nodes/$nodeId/metrics") { bearerAuth(tokenFor(userId)) }
            .body<JsonObject>()

        assertTrue(body["timestamps"]!!.jsonArray.isEmpty())
        assertTrue(body["cpu_percent"]!!.jsonArray.isEmpty())
        assertTrue(body["ram_used_mb"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `metrics returns columnar data for recorded metrics`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        createNodeMetric(nodeId, cpuPercent = 55.0, ramUsedMb = 3000)
        createNodeMetric(nodeId, cpuPercent = 60.0, ramUsedMb = 3500)
        val client = jsonClient()

        val body = client.get("/api/nodes/$nodeId/metrics") { bearerAuth(tokenFor(userId)) }
            .body<JsonObject>()

        assertEquals(2, body["timestamps"]!!.jsonArray.size)
        assertEquals(2, body["cpu_percent"]!!.jsonArray.size)
        assertEquals(2, body["ram_used_mb"]!!.jsonArray.size)
        assertEquals(2, body["net_in_bytes"]!!.jsonArray.size)
        assertEquals(2, body["disk_used_bytes"]!!.jsonArray.size)
    }

    @Test
    fun `metrics limit parameter caps the number of returned rows`() = testApplication {
        application { configureTest() }
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        repeat(10) { createNodeMetric(nodeId) }
        val client = jsonClient()

        val body = client.get("/api/nodes/$nodeId/metrics?limit=5") { bearerAuth(tokenFor(userId)) }
            .body<JsonObject>()

        assertEquals(5, body["timestamps"]!!.jsonArray.size)
    }
}
