package io.craftpanel.master.routes

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.TokenClaims
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.PortRegistry
import io.craftpanel.master.database.schema.ServerMigrations
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
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
import kotlin.test.assertTrue

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
        routing { serversRoutes() }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun createUser(username: String = "admin", email: String = "admin@example.com"): UUID = transaction {
        Users.insert {
            it[Users.username] = username
            it[Users.email] = email
            it[Users.passwordHash] = Argon2Hasher.hash("hunter2")
            it[Users.isActive] = true
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

    private fun createNode(
        hostname: String = "node-1",
        status: String = "ACTIVE",
        totalRamMb: Int = 8192,
        totalCpuShares: Int = 0,
        portStart: Int = 25565,
        portEnd: Int = 25600,
    ): UUID = transaction {
        Nodes.insert {
            it[Nodes.hostname] = hostname
            it[Nodes.displayName] = hostname
            it[Nodes.publicIp] = "1.2.3.4"
            it[Nodes.privateIp] = "10.0.0.1"
            it[Nodes.tokenHash] = "aa${hostname.hashCode().toString(16).padStart(62, '0')}".take(64)
            it[Nodes.status] = status
            it[Nodes.totalRamMb] = totalRamMb
            it[Nodes.totalCpuShares] = totalCpuShares
            it[Nodes.portRangeStart] = portStart
            it[Nodes.portRangeEnd] = portEnd
        }[Nodes.id].let { UUID.fromString(it.toString()) }
    }

    private fun createNetwork(name: String = "net-1"): UUID = transaction {
        ServerNetworks.insert {
            it[ServerNetworks.name] = name
            it[ServerNetworks.type] = "VANILLA"
        }[ServerNetworks.id].let { UUID.fromString(it.toString()) }
    }

    private fun createServer(
        nodeId: UUID,
        name: String = "server-${UUID.randomUUID()}",
        networkId: UUID? = null,
        status: String = "STOPPED",
        memoryMb: Int = 1024,
        port: Int = 25565,
    ): UUID = transaction {
        Servers.insert {
            it[Servers.nodeId] = nodeId.toKotlinUuid()
            it[Servers.networkId] = networkId?.toKotlinUuid()
            it[Servers.name] = name
            it[Servers.displayName] = name
            it[Servers.serverType] = "VANILLA"
            it[Servers.gamePort] = port
            it[Servers.memoryMb] = memoryMb
            it[Servers.cpuShares] = 0
            it[Servers.status] = status
        }[Servers.id].let { UUID.fromString(it.toString()) }
    }

    // ── GET /servers ─────────────────────────────────────────────────────────

    @Test
    fun `GET servers returns 401 without token`() = testApplication {
        application { configureTest() }
        val resp = client.get("/api/v1/servers")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `GET servers returns empty list for user with no permissions`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        val nodeId = createNode()
        createServer(nodeId, "hidden")
        val resp = client.get("/api/v1/servers") { bearerAuth(tokenFor(userId)) }
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
        val resp = client.get("/api/v1/servers") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<List<JsonObject>>()
        assertEquals(2, body.size)
        val names = body.map { it["name"]!!.jsonPrimitive.content }.toSet()
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
                it[ServerMigrations.serverId] = serverId.toKotlinUuid()
                it[ServerMigrations.sourceNodeId] = nodeId.toKotlinUuid()
                it[ServerMigrations.targetNodeId] = nodeId.toKotlinUuid()
                it[ServerMigrations.status] = "RUNNING"
            }
        }
        val resp = client.get("/api/v1/servers") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.OK, resp.status)
        val server = resp.body<List<JsonObject>>().first { it["name"]!!.jsonPrimitive.content == "migrating-server" }
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
        val resp = client.post("/api/v1/servers") {
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
        val resp = client.post("/api/v1/servers") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"name":"new-server","display_name":"New Server","node_id":"$nodeId","server_type":"PAPER","memory_mb":2048,"cpu_shares":0}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val body = resp.body<JsonObject>()
        assertEquals("new-server", body["name"]!!.jsonPrimitive.content)
        assertEquals("New Server", body["display_name"]!!.jsonPrimitive.content)
        assertEquals("PAPER", body["server_type"]!!.jsonPrimitive.content)
        assertEquals(25565, body["game_port"]!!.jsonPrimitive.content.toInt())
        assertEquals("false", body["is_migrating"]!!.jsonPrimitive.content)
        assertNotNull(body["id"])

        val portCount = transaction {
            PortRegistry.selectAll().where { PortRegistry.nodeId eq nodeId.toKotlinUuid() }.count()
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
            val resp = client.post("/api/v1/servers") {
                bearerAuth(tokenFor(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"srv-$i","node_id":"$nodeId","server_type":"VANILLA","memory_mb":512}""")
            }
            assertEquals(HttpStatusCode.Created, resp.status)
            val port = resp.body<JsonObject>()["game_port"]!!.jsonPrimitive.content.toInt()
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
        val resp = client.post("/api/v1/servers") {
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
        val resp = client.post("/api/v1/servers") {
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
        val resp = client.post("/api/v1/servers") {
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
        val resp = client.post("/api/v1/servers") {
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
        val fakeNetId = UUID.randomUUID()
        val resp = client.post("/api/v1/servers") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"name":"srv","node_id":"$nodeId","network_id":"$fakeNetId","server_type":"VANILLA","memory_mb":512}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
    }

    // ── GET /servers/{id} ────────────────────────────────────────────────────

    @Test
    fun `GET server by id returns 404 for unknown`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Viewer")
        val resp = client.get("/api/v1/servers/${UUID.randomUUID()}") { bearerAuth(tokenFor(userId)) }
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
        val resp = client.get("/api/v1/servers/$serverId") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<JsonObject>()
        assertEquals("detail-srv", body["name"]!!.jsonPrimitive.content)
        assertNotNull(body["is_migrating"])
        assertNotNull(body["game_port"])
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
        val resp = client.get("/api/v1/servers/$serverId") { bearerAuth(tokenFor(userId)) }
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
        val resp = client.patch("/api/v1/servers/$serverId") {
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
        val resp = client.patch("/api/v1/servers/$serverId") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"display_name":"Patched Name"}""")
        }
        assertEquals(HttpStatusCode.NoContent, resp.status)
        val row = transaction { Servers.selectAll().where { Servers.id eq serverId.toKotlinUuid() }.first() }
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
        val resp = client.patch("/api/v1/servers/$serverId") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"network_id":null}""")
        }
        assertEquals(HttpStatusCode.NoContent, resp.status)
        val row = transaction { Servers.selectAll().where { Servers.id eq serverId.toKotlinUuid() }.first() }
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
        val resp = client.patch("/api/v1/servers/$serverId") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"display_name":"No Network Change"}""")
        }
        assertEquals(HttpStatusCode.NoContent, resp.status)
        val row = transaction { Servers.selectAll().where { Servers.id eq serverId.toKotlinUuid() }.first() }
        assertEquals(netId.toKotlinUuid(), row[Servers.networkId])
    }

    @Test
    fun `PATCH server returns 422 for nonexistent network_id`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        val fakeNetId = UUID.randomUUID()
        val resp = client.patch("/api/v1/servers/$serverId") {
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
        val serverId = createServer(nodeId, status = "RUNNING")
        val resp = client.delete("/api/v1/servers/$serverId") { bearerAuth(tokenFor(userId)) }
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
                it[PortRegistry.nodeId] = nodeId.toKotlinUuid()
                it[PortRegistry.port] = 25565
                it[PortRegistry.protocol] = "TCP"
                it[PortRegistry.serverId] = serverId.toKotlinUuid()
            }
        }
        val resp = client.delete("/api/v1/servers/$serverId") { bearerAuth(tokenFor(userId)) }
        assertEquals(HttpStatusCode.NoContent, resp.status)

        val serverExists = transaction { Servers.selectAll().where { Servers.id eq serverId.toKotlinUuid() }.firstOrNull() != null }
        assertEquals(false, serverExists)
        val portExists = transaction { PortRegistry.selectAll().where { PortRegistry.serverId eq serverId.toKotlinUuid() }.firstOrNull() != null }
        assertEquals(false, portExists)
    }

    @Test
    fun `DELETE server returns 404 for unknown`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val resp = client.delete("/api/v1/servers/${UUID.randomUUID()}") { bearerAuth(tokenFor(userId)) }
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
        val resp = client.patch("/api/v1/servers/$serverId/resources") {
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
        val resp = client.patch("/api/v1/servers/$serverId/resources") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"memory_mb":3000,"cpu_shares":512}""")
        }
        assertEquals(HttpStatusCode.NoContent, resp.status)
        val row = transaction { Servers.selectAll().where { Servers.id eq serverId.toKotlinUuid() }.first() }
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
        val resp = client.patch("/api/v1/servers/$serverId/resources") {
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
        val resp = client.patch("/api/v1/servers/$serverId/resources") {
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
        val resp = client.patch("/api/v1/servers/$serverId/exposure") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"exposed_externally":true,"public_subdomain":"myserver"}""")
        }
        assertEquals(HttpStatusCode.NoContent, resp.status)
        val row = transaction { Servers.selectAll().where { Servers.id eq serverId.toKotlinUuid() }.first() }
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
        client.patch("/api/v1/servers/$s1/exposure") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"exposed_externally":true,"public_subdomain":"clash"}""")
        }
        val resp = client.patch("/api/v1/servers/$s2/exposure") {
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
        val resp = client.patch("/api/v1/servers/$serverId/exposure") {
            bearerAuth(tokenFor(userId))
            contentType(ContentType.Application.Json)
            setBody("""{"exposed_externally":false}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }
}
