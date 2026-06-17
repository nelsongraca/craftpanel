package io.craftpanel.master.routes

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.proto.MasterMessage
import io.craftpanel.master.TestDatabase
import kotlinx.coroutines.flow.MutableSharedFlow
import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.TokenClaims
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.*
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

import kotlin.test.*
import io.craftpanel.master.service.ForbiddenException as ServiceForbiddenException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

class ServersRoutesTest {

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

    private fun Application.configureTest(
        sendToNode: (String, MasterMessage) -> Boolean = { _, _ -> true },
        agentEvents: MutableSharedFlow<AgentEvent> = MutableSharedFlow(extraBufferCapacity = 16),
    ) {
        install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(StatusPages) {
            exception<NotFoundException> { call, ex -> call.respond(HttpStatusCode.NotFound, mapOf("error" to (ex.message ?: "Not found"))) }
            exception<ServiceForbiddenException> { call, ex -> call.respond(HttpStatusCode.Forbidden, mapOf("error" to (ex.message ?: "Forbidden"))) }
            exception<ConflictException> { call, ex -> call.respond(HttpStatusCode.Conflict, mapOf("error" to (ex.message ?: "Conflict"))) }
            exception<UnprocessableException> { call, ex -> call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to (ex.message ?: "Unprocessable"))) }
            exception<BadGatewayException> { call, ex -> call.respond(HttpStatusCode.BadGateway, mapOf("error" to (ex.message ?: "Bad gateway"))) }
            exception<BadRequestException> { call, ex -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to (ex.message ?: "Bad request"))) }
            exception<ContainerLifecycleException> { call, ex -> call.respond(HttpStatusCode.BadGateway, mapOf("error" to (ex.message ?: "Lifecycle error"))) }
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
        val lifecycle = ContainerLifecycle(
            sendToNode = sendToNode,
            agentEvents = agentEvents,
            modService = ModService(),
        )
        routing { serversRoutes(ServerService(sendToNode, ModService(), lifecycle = lifecycle)) }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun createUser(username: String = "admin", email: String = "admin@example.com"): Uuid = transaction {
        Users.insert {
            it[Users.username] = username
            it[Users.email] = email
            it[Users.passwordHash] = Argon2Hasher.hash("hunter2")
            it[Users.isActive] = true
        }[Users.id].let { Uuid.parse(it.toString()) }
    }

    private fun assignGlobalGroup(userId: Uuid, groupName: String) = transaction {
        val groupId = Groups.selectAll()
            .where { Groups.name eq groupName }
            .first()[Groups.id]
        UserGroupAssignments.insert {
            it[UserGroupAssignments.userId] = userId
            it[UserGroupAssignments.groupId] = groupId
            it[UserGroupAssignments.scopeType] = "GLOBAL"
        }
    }

    private fun tokenFor(userId: Uuid, username: String = "admin"): String =
        jwtManager.generate(TokenClaims(userId = userId, name = username, email = "$username@example.com", groups = emptyList()))

    private fun createNode(
        hostname: String = "node-1",
        status: String = "ACTIVE",
        totalRamMb: Int = 8192,
        totalCpuShares: Int = 0,
        portStart: Int = 25565,
        portEnd: Int = 25600,
    ): Uuid = transaction {
        Nodes.insert {
            it[Nodes.hostname] = hostname
            it[Nodes.displayName] = hostname
            it[Nodes.publicIp] = "1.2.3.4"
            it[Nodes.privateIp] = "10.0.0.1"
            it[Nodes.tokenHash] = "aa${
                hostname.hashCode()
                    .toString(16)
                    .padStart(62, '0')
            }".take(64)
            it[Nodes.status] = status
            it[Nodes.totalRamMb] = totalRamMb
            it[Nodes.totalCpuShares] = totalCpuShares
            it[Nodes.portRangeStart] = portStart
            it[Nodes.portRangeEnd] = portEnd
        }[Nodes.id].let { Uuid.parse(it.toString()) }
    }

    private fun createNetwork(name: String = "net-1"): Uuid = transaction {
        ServerNetworks.insert {
            it[ServerNetworks.name] = name
            it[ServerNetworks.type] = "VANILLA"
        }[ServerNetworks.id].let { Uuid.parse(it.toString()) }
    }

    private fun createServer(
        nodeId: Uuid,
        name: String = "server-${Uuid.random()}",
        networkId: Uuid? = null,
        status: String = "STOPPED",
        memoryMb: Int = 1024,
        port: Int = 25565,
        mcVersion: String = "1.21.4",
    ): Uuid = transaction {
        Servers.insert {
            it[Servers.nodeId] = nodeId
            it[Servers.networkId] = networkId
            it[Servers.name] = name
            it[Servers.displayName] = name
            it[Servers.serverType] = "VANILLA"
            it[Servers.mcVersion] = mcVersion
            it[Servers.hostPort] = port
            it[Servers.memoryMb] = memoryMb
            it[Servers.cpuShares] = 0
            it[Servers.status] = status
        }[Servers.id].let { Uuid.parse(it.toString()) }
    }

    // ── GET /servers ─────────────────────────────────────────────────────────

    @Test
    fun `GET servers returns 401 without token`() = testApplication {
        application { configureTest() }
        val resp = client.get("/api/servers")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `GET servers returns empty list for user with no permissions`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        val nodeId = createNode()
        createServer(nodeId, "hidden")
        val resp = client.get("/api/servers") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(0, resp.body<List<JsonObject>>().size)
    }

    @Test
    fun `GET servers returns all servers for global viewer`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Viewer")
        val nodeId = createNode()
        createServer(nodeId, "s1")
        createServer(nodeId, "s2")
        val resp = client.get("/api/servers") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<List<JsonObject>>()
        assertEquals(2, body.size)
        val names = body.map { it["name"]!!.jsonPrimitive.content }
            .toSet()
        assertEquals(setOf("s1", "s2"), names)
    }

    @Test
    fun `GET servers includes is_migrating field`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Viewer")
        val nodeId = createNode()
        val serverId = createServer(nodeId, "migrating-server")
        transaction {
            ServerMigrations.insert {
                it[ServerMigrations.serverId] = serverId
                it[ServerMigrations.sourceNodeId] = nodeId
                it[ServerMigrations.targetNodeId] = nodeId
                it[ServerMigrations.status] = "RUNNING"
            }
        }
        val resp = client.get("/api/servers") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.OK, resp.status)
        val server = resp.body<List<JsonObject>>()
            .first { it["name"]!!.jsonPrimitive.content == "migrating-server" }
        assertEquals("true", server["is_migrating"]!!.jsonPrimitive.content)
    }

    // ── POST /servers ─────────────────────────────────────────────────────────

    @Test
    fun `POST servers returns 403 without server-create`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Viewer")
        val nodeId = createNode()
        val resp = client.post("/api/servers") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"name":"s","node_id":"$nodeId","server_type":"VANILLA","memory_mb":1024}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `POST servers creates server and allocates port`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val resp = client.post("/api/servers") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"name":"new-server","display_name":"New Server","node_id":"$nodeId","server_type":"PAPER","memory_mb":2048,"cpu_shares":0}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val body = resp.body<JsonObject>()
        assertEquals("new-server", body["name"]!!.jsonPrimitive.content)
        assertEquals("New Server", body["display_name"]!!.jsonPrimitive.content)
        assertEquals("PAPER", body["server_type"]!!.jsonPrimitive.content)
        assertEquals(25565, body["host_port"]!!.jsonPrimitive.content.toInt())
        assertEquals("false", body["is_migrating"]!!.jsonPrimitive.content)
        assertNotNull(body["id"])

        val portCount = transaction {
            PortRegistry.selectAll()
                .where { PortRegistry.nodeId eq nodeId }
                .count()
        }
        assertEquals(1L, portCount)
    }

    @Test
    fun `POST servers allocates sequential ports`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode(totalRamMb = 8192)
        repeat(3) { i ->
            val resp = client.post("/api/servers") {
                bearerAuth(tokenFor(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"srv-$i","node_id":"$nodeId","server_type":"VANILLA","memory_mb":512}""")
            }
            assertEquals(HttpStatusCode.Created, resp.status)
            val port = resp.body<JsonObject>()["host_port"]!!.jsonPrimitive.content.toInt()
            assertEquals(25565 + i, port)
        }
    }

    @Test
    fun `POST servers returns 422 when node not ACTIVE`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode(status = "PENDING")
        val resp = client.post("/api/servers") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"name":"srv","node_id":"$nodeId","server_type":"VANILLA","memory_mb":1024}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
    }

    @Test
    fun `POST servers returns 409 when insufficient RAM`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode(totalRamMb = 2048)
        createServer(nodeId, memoryMb = 1024)
        val resp = client.post("/api/servers") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"name":"big","node_id":"$nodeId","server_type":"VANILLA","memory_mb":1025}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `POST servers returns 409 on duplicate name`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        createServer(nodeId, "dup-name")
        val resp = client.post("/api/servers") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"name":"dup-name","node_id":"$nodeId","server_type":"VANILLA","memory_mb":512}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `POST servers returns 422 for invalid node_id`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val resp = client.post("/api/servers") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"name":"srv","node_id":"not-a-uuid","server_type":"VANILLA","memory_mb":512}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
    }

    @Test
    fun `POST servers returns 422 for nonexistent network_id`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val fakeNetId = Uuid.random()
        val resp = client.post("/api/servers") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"name":"srv","node_id":"$nodeId","network_id":"$fakeNetId","server_type":"VANILLA","memory_mb":512}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
    }

    @Test
    fun `POST servers stores mc_version and itzg_image_tag`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val resp = client.post("/api/servers") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"name":"versioned","node_id":"$nodeId","server_type":"PAPER","mc_version":"1.21.4","itzg_image_tag":"java21","memory_mb":1024}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val body = resp.body<JsonObject>()
        assertEquals("1.21.4", body["mc_version"]!!.jsonPrimitive.content)
        assertEquals("java21", body["itzg_image_tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `POST servers derives stop_command for proxy types`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val resp = client.post("/api/servers") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"name":"proxy-srv","node_id":"$nodeId","server_type":"VELOCITY","memory_mb":512}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val id = Uuid.parse(resp.body<JsonObject>()["id"]!!.jsonPrimitive.content)
        val row = transaction {
            Servers.selectAll()
                .where { Servers.id eq id }
                .first()
        }
        assertEquals("end", row[Servers.stopCommand])
    }

    // ── GET /servers/{id} ────────────────────────────────────────────────────

    @Test
    fun `GET server by id returns 404 for unknown`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Viewer")
        val resp = client.get("/api/servers/${Uuid.random()}") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `GET server by id returns full object for viewer`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Viewer")
        val nodeId = createNode()
        val serverId = createServer(nodeId, "detail-srv")
        val resp = client.get("/api/servers/$serverId") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<JsonObject>()
        assertEquals("detail-srv", body["name"]!!.jsonPrimitive.content)
        assertNotNull(body["is_migrating"])
        assertNotNull(body["host_port"])
        assertNotNull(body["created_at"])
        assertNotNull(body["updated_at"])
    }

    @Test
    fun `GET server by id returns 403 for user with no permissions`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        val nodeId = createNode()
        val serverId = createServer(nodeId, "private-srv")
        val resp = client.get("/api/servers/$serverId") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    // ── PATCH /servers/{id} ──────────────────────────────────────────────────

    @Test
    fun `PATCH server returns 403 without server-configure`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Viewer")
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        val resp = client.patch("/api/servers/$serverId") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"display_name":"New Name"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `PATCH server updates display_name`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId, "to-patch")
        val resp = client.patch("/api/servers/$serverId") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"display_name":"Patched Name"}""")
        }
        assertEquals(HttpStatusCode.NoContent, resp.status)
        val row = transaction {
            Servers.selectAll()
                .where { Servers.id eq serverId }
                .first()
        }
        assertEquals("Patched Name", row[Servers.displayName])
    }

    @Test
    fun `PATCH server clears network_id when null`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val netId = createNetwork()
        val serverId = createServer(nodeId, networkId = netId)
        val resp = client.patch("/api/servers/$serverId") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"network_id":""}""")
        }
        assertEquals(HttpStatusCode.NoContent, resp.status)
        val row = transaction {
            Servers.selectAll()
                .where { Servers.id eq serverId }
                .first()
        }
        assertEquals(null, row[Servers.networkId])
    }

    @Test
    fun `PATCH server skips network_id when key absent`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val netId = createNetwork()
        val serverId = createServer(nodeId, networkId = netId)
        val resp = client.patch("/api/servers/$serverId") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"display_name":"No Network Change"}""")
        }
        assertEquals(HttpStatusCode.NoContent, resp.status)
        val row = transaction {
            Servers.selectAll()
                .where { Servers.id eq serverId }
                .first()
        }
        assertEquals(netId, row[Servers.networkId])
    }

    @Test
    fun `PATCH server returns 422 for nonexistent network_id`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        val fakeNetId = Uuid.random()
        val resp = client.patch("/api/servers/$serverId") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"network_id":"$fakeNetId"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
    }

    // ── DELETE /servers/{id} ─────────────────────────────────────────────────

    @Test
    fun `DELETE server returns 409 when not STOPPED`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "HEALTHY")
        val resp = client.delete("/api/servers/$serverId") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `DELETE server removes server and port registry entry`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId, "to-delete", status = "STOPPED")
        transaction {
            PortRegistry.insert {
                it[PortRegistry.nodeId] = nodeId
                it[PortRegistry.port] = 25565
                it[PortRegistry.protocol] = "TCP"
                it[PortRegistry.serverId] = serverId
            }
        }
        val resp = client.delete("/api/servers/$serverId") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.NoContent, resp.status)

        val serverExists = transaction {
            Servers.selectAll()
                .where { Servers.id eq serverId }
                .firstOrNull() != null
        }
        assertEquals(false, serverExists)
        val portExists = transaction {
            PortRegistry.selectAll()
                .where { PortRegistry.serverId eq serverId }
                .firstOrNull() != null
        }
        assertEquals(false, portExists)
    }

    @Test
    fun `DELETE server returns 404 for unknown`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val resp = client.delete("/api/servers/${Uuid.random()}") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    // ── PATCH /servers/{id}/resources ────────────────────────────────────────

    @Test
    fun `PATCH resources returns 403 without server-resources permission`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Server Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        val resp = client.patch("/api/servers/$serverId/resources") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"memory_mb":2048,"cpu_shares":0}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `PATCH resources updates memory and cpu`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode(totalRamMb = 8192)
        val serverId = createServer(nodeId, memoryMb = 1024)
        val resp = client.patch("/api/servers/$serverId/resources") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"memory_mb":3000,"cpu_shares":512}""")
        }
        assertEquals(HttpStatusCode.NoContent, resp.status)
        val row = transaction {
            Servers.selectAll()
                .where { Servers.id eq serverId }
                .first()
        }
        assertEquals(3000, row[Servers.memoryMb])
        assertEquals(512, row[Servers.cpuShares])
    }

    @Test
    fun `PATCH resources returns 409 when insufficient RAM`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode(totalRamMb = 4096)
        createServer(nodeId, "other", memoryMb = 2048, port = 25566)
        val serverId = createServer(nodeId, "target", memoryMb = 1024)
        val resp = client.patch("/api/servers/$serverId/resources") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"memory_mb":3000,"cpu_shares":0}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `PATCH resources validates memory_mb positive`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        val resp = client.patch("/api/servers/$serverId/resources") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"memory_mb":0,"cpu_shares":0}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
    }

    // ── PATCH /servers/{id}/exposure ─────────────────────────────────────────

    @Test
    fun `PATCH exposure persists values`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId, "expose-me")
        val resp = client.patch("/api/servers/$serverId/exposure") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"exposed_externally":true,"public_subdomain":"myserver"}""")
        }
        assertEquals(HttpStatusCode.NoContent, resp.status)
        val row = transaction {
            Servers.selectAll()
                .where { Servers.id eq serverId }
                .first()
        }
        assertEquals(true, row[Servers.exposedExternally])
        assertEquals("myserver", row[Servers.publicSubdomain])
    }

    @Test
    fun `PATCH exposure returns 422 when subdomain already taken`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val s1 = createServer(nodeId, "srv-1", port = 25565)
        val s2 = createServer(nodeId, "srv-2", port = 25566)
        client.patch("/api/servers/$s1/exposure") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"exposed_externally":true,"public_subdomain":"clash"}""")
        }
        val resp = client.patch("/api/servers/$s2/exposure") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"exposed_externally":true,"public_subdomain":"clash"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
    }

    @Test
    fun `PATCH exposure returns 403 without server-configure`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Viewer")
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        val resp = client.patch("/api/servers/$serverId/exposure") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"exposed_externally":false}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    // ── POST /servers/{id}/start ─────────────────────────────────────────────

    @Test
    fun `POST start returns 401 without token`() = testApplication {
        application { configureTest() }
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        val resp = client.post("/api/servers/$serverId/start")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `POST start returns 403 without server-start permission`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Viewer")
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        val resp = client.post("/api/servers/$serverId/start") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `POST start returns 404 for unknown server`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val resp = client.post("/api/servers/${Uuid.random()}/start") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `POST start returns 409 if server is HEALTHY`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "HEALTHY")
        val resp = client.post("/api/servers/$serverId/start") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `POST start returns 409 if server is STARTING`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "STARTING")
        val resp = client.post("/api/servers/$serverId/start") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `POST start returns 502 when agent not connected`() = testApplication {
        application { configureTest(sendToNode = { _, _ -> false }) }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "STOPPED")
        val resp = client.post("/api/servers/$serverId/start") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.BadGateway, resp.status)
    }

    @Test
    fun `POST start returns 202 and updates status to STARTING`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "STOPPED")
        val resp = client.post("/api/servers/$serverId/start") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.Accepted, resp.status)
        val row = transaction {
            Servers.selectAll()
                .where { Servers.id eq serverId }
                .first()
        }
        assertEquals("STARTING", row[Servers.status])
    }

    @Test
    fun `POST start sends single StartContainerCommand with needsRecreate=false`() = testApplication {
        val sentCommands = mutableListOf<MasterMessage>()
        application {
            configureTest(
                sendToNode = { _, msg ->
                    sentCommands.add(msg)
                    true
                },
            )
        }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "STOPPED")
        val resp = client.post("/api/servers/$serverId/start") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.Accepted, resp.status)
        assertEquals(1, sentCommands.size)
        assertTrue(sentCommands[0].hasStartContainer())
        val cmd = sentCommands[0].startContainer
        assertEquals("craftpanel-$serverId", cmd.containerName)
        assertEquals("itzg/minecraft-server:latest", cmd.image)
        assertEquals("TRUE", cmd.envVarsMap["EULA"])
        assertTrue(!cmd.needsRecreate)
    }

    @Test
    fun `POST start sends StartContainerCommand with needsRecreate=true when server needs recreate`() = testApplication {
        val sentCommands = mutableListOf<MasterMessage>()
        application {
            configureTest(
                sendToNode = { _, msg ->
                    sentCommands.add(msg)
                    true
                },
            )
        }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "STOPPED")
        transaction { Servers.update({ Servers.id eq serverId }) { it[Servers.needsRecreate] = true } }
        val resp = client.post("/api/servers/$serverId/start") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.Accepted, resp.status)
        assertEquals(1, sentCommands.size)
        assertTrue(sentCommands[0].hasStartContainer())
        assertTrue(sentCommands[0].startContainer.needsRecreate)
    }

    // ── POST /servers/{id}/stop ──────────────────────────────────────────────

    @Test
    fun `POST stop returns 403 without server-stop permission`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Viewer")
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "HEALTHY")
        val resp = client.post("/api/servers/$serverId/stop") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `POST stop returns 409 if server is already STOPPED`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "STOPPED")
        val resp = client.post("/api/servers/$serverId/stop") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `POST stop returns 502 when agent not connected`() = testApplication {
        application { configureTest(sendToNode = { _, _ -> false }) }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "HEALTHY")
        val resp = client.post("/api/servers/$serverId/stop") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.BadGateway, resp.status)
    }

    @Test
    fun `POST stop returns 202 and sends StopContainerCommand`() = testApplication {
        val sentCommands = mutableListOf<MasterMessage>()
        application { configureTest(sendToNode = { _, msg -> sentCommands.add(msg); true }) }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "HEALTHY")
        val resp = client.post("/api/servers/$serverId/stop") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.Accepted, resp.status)
        assertEquals(1, sentCommands.size)
        assertTrue(sentCommands[0].hasStopContainer())
        assertEquals("craftpanel-$serverId", sentCommands[0].stopContainer.containerName)
    }

    // ── POST /servers/{id}/restart ───────────────────────────────────────────

    @Test
    fun `POST restart returns 403 without server-restart permission`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Viewer")
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "HEALTHY")
        val resp = client.post("/api/servers/$serverId/restart") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `POST restart returns 502 when agent not connected`() = testApplication {
        application { configureTest(sendToNode = { _, _ -> false }) }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "HEALTHY")
        val resp = client.post("/api/servers/$serverId/restart") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.BadGateway, resp.status)
    }

    @Test
    fun `POST restart returns 202 and sends RestartContainerCommand`() = testApplication {
        val sentCommands = mutableListOf<MasterMessage>()
        application { configureTest(sendToNode = { _, msg -> sentCommands.add(msg); true }) }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId, status = "HEALTHY")
        val resp = client.post("/api/servers/$serverId/restart") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.Accepted, resp.status)
        assertEquals(1, sentCommands.size)
        assertTrue(sentCommands[0].hasRestartContainer())
        assertEquals("craftpanel-$serverId", sentCommands[0].restartContainer.containerName)
    }
}
