package io.craftpanel.master.routes

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.TokenClaims
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.ServerMods
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
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ModsRoutesTest {

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
                    call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "Unauthorized"))
                }
            }
        }
        routing { modsRoutes() }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun createUser(email: String = "admin@example.com"): UUID = transaction {
        Users.insert {
            it[Users.username] = email.substringBefore("@")
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

    private fun tokenFor(userId: UUID): String =
        jwtManager.generate(TokenClaims(userId = userId, name = "admin", email = "admin@example.com", groups = emptyList()))

    private fun createNode(): UUID = transaction {
        Nodes.insert {
            it[Nodes.hostname] = "node-1"
            it[Nodes.displayName] = "node-1"
            it[Nodes.publicIp] = "1.2.3.4"
            it[Nodes.privateIp] = "10.0.0.1"
            it[Nodes.tokenHash] = "a".repeat(64)
            it[Nodes.status] = "ACTIVE"
            it[Nodes.totalRamMb] = 8192
            it[Nodes.totalCpuShares] = 1024
        }[Nodes.id].let { UUID.fromString(it.toString()) }
    }

    private fun createServer(nodeId: UUID): UUID = transaction {
        Servers.insert {
            it[Servers.name] = "test-server"
            it[Servers.displayName] = "Test Server"
            it[Servers.nodeId] = nodeId.toKotlinUuid()
            it[Servers.serverType] = "VANILLA"
            it[Servers.mcVersion] = "LATEST"
            it[Servers.memoryMb] = 1024
            it[Servers.hostPort] = 25565
        }[Servers.id].let { UUID.fromString(it.toString()) }
    }

    // ── List mods ─────────────────────────────────────────────────────────────

    @Test
    fun `list mods returns empty list when none exist`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()
        val serverId = createServer(nodeId)

        val res = client.get("/api/servers/$serverId/mods") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(0, res.body<JsonObject>()["mods"]!!.jsonArray.size)
    }

    @Test
    fun `list mods requires server_mods permission`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Viewer")
        val token = tokenFor(userId)
        val nodeId = createNode()
        val serverId = createServer(nodeId)

        val res = client.get("/api/servers/$serverId/mods") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    // ── Add mod ───────────────────────────────────────────────────────────────

    @Test
    fun `add mod with LATEST strategy succeeds`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()
        val serverId = createServer(nodeId)

        val res = client.post("/api/servers/$serverId/mods") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"modrinth_project_id":"fabric-api","display_name":"Fabric API","pin_strategy":"LATEST"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        val body = res.body<JsonObject>()
        assertEquals("fabric-api", body["modrinth_project_id"]!!.jsonPrimitive.content)
        assertEquals("LATEST", body["pin_strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `add mod with PINNED strategy requires pinned_version_id`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()
        val serverId = createServer(nodeId)

        val res = client.post("/api/servers/$serverId/mods") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"modrinth_project_id":"fabric-api","display_name":"Fabric API","pin_strategy":"PINNED"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
    }

    @Test
    fun `add mod with PINNED strategy and version succeeds`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()
        val serverId = createServer(nodeId)

        val res = client.post("/api/servers/$serverId/mods") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"modrinth_project_id":"fabric-api","display_name":"Fabric API","pin_strategy":"PINNED","pinned_version_id":"Oa9ZDzZq"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        val body = res.body<JsonObject>()
        assertEquals("Oa9ZDzZq", body["pinned_version_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `add mod with BETA strategy succeeds`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()
        val serverId = createServer(nodeId)

        val res = client.post("/api/servers/$serverId/mods") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"modrinth_project_id":"fabric-api","display_name":"Fabric API","pin_strategy":"BETA"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        assertEquals("BETA", res.body<JsonObject>()["pin_strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `add mod with ALPHA strategy succeeds`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()
        val serverId = createServer(nodeId)

        val res = client.post("/api/servers/$serverId/mods") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"modrinth_project_id":"fabric-api","display_name":"Fabric API","pin_strategy":"ALPHA"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)
        assertEquals("ALPHA", res.body<JsonObject>()["pin_strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `add duplicate mod returns 409`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()
        val serverId = createServer(nodeId)

        val body = """{"modrinth_project_id":"fabric-api","display_name":"Fabric API","pin_strategy":"LATEST"}"""
        client.post("/api/servers/$serverId/mods") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val res = client.post("/api/servers/$serverId/mods") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Conflict, res.status)
    }

    @Test
    fun `add mod with invalid pin_strategy returns 422`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()
        val serverId = createServer(nodeId)

        val res = client.post("/api/servers/$serverId/mods") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"modrinth_project_id":"fabric-api","display_name":"Fabric API","pin_strategy":"NIGHTLY"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, res.status)
    }

    // ── Patch mod ─────────────────────────────────────────────────────────────

    @Test
    fun `patch mod updates pin_strategy`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()
        val serverId = createServer(nodeId)

        val modId = transaction {
            ServerMods.insert {
                it[ServerMods.serverId] = serverId.toKotlinUuid()
                it[ServerMods.modrinthProjectId] = "fabric-api"
                it[ServerMods.displayName] = "Fabric API"
                it[ServerMods.pinStrategy] = "LATEST"
            }[ServerMods.id]
        }

        val res = client.patch("/api/servers/$serverId/mods/$modId") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"pin_strategy":"PINNED","pinned_version_id":"Oa9ZDzZq"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.body<JsonObject>()
        assertEquals("PINNED", body["pin_strategy"]!!.jsonPrimitive.content)
        assertEquals("Oa9ZDzZq", body["pinned_version_id"]!!.jsonPrimitive.content)
    }

    // ── Delete mod ────────────────────────────────────────────────────────────

    @Test
    fun `delete mod removes it`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()
        val serverId = createServer(nodeId)

        val modId = transaction {
            ServerMods.insert {
                it[ServerMods.serverId] = serverId.toKotlinUuid()
                it[ServerMods.modrinthProjectId] = "fabric-api"
                it[ServerMods.displayName] = "Fabric API"
                it[ServerMods.pinStrategy] = "LATEST"
            }[ServerMods.id]
        }

        val res = client.delete("/api/servers/$serverId/mods/$modId") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.NoContent, res.status)
        assertEquals(0, transaction { ServerMods.selectAll().where { ServerMods.id eq modId }.count() })
    }

    @Test
    fun `delete nonexistent mod returns 404`() = testApplication {
        application { configureTest() }
        val client = jsonClient()
        val userId = createUser()
        assignGlobalGroup(userId, "Super Admin")
        val token = tokenFor(userId)
        val nodeId = createNode()
        val serverId = createServer(nodeId)

        val res = client.delete("/api/servers/$serverId/mods/${UUID.randomUUID()}") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    // ── MODRINTH_PROJECTS serialization ───────────────────────────────────────

    @Test
    fun `modrinthProjectsEnvVar serializes LATEST correctly`() {
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        transaction {
            ServerMods.insert {
                it[ServerMods.serverId] = serverId.toKotlinUuid()
                it[ServerMods.modrinthProjectId] = "fabric-api"
                it[ServerMods.displayName] = "Fabric API"
                it[ServerMods.pinStrategy] = "LATEST"
            }
        }
        val mods = transaction { ServerMods.selectAll().where { ServerMods.serverId eq serverId.toKotlinUuid() }.toList() }
        assertEquals("fabric-api", modrinthProjectsEnvVar(mods))
    }

    @Test
    fun `modrinthProjectsEnvVar serializes PINNED correctly`() {
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        transaction {
            ServerMods.insert {
                it[ServerMods.serverId] = serverId.toKotlinUuid()
                it[ServerMods.modrinthProjectId] = "fabric-api"
                it[ServerMods.displayName] = "Fabric API"
                it[ServerMods.pinStrategy] = "PINNED"
                it[ServerMods.pinnedVersionId] = "Oa9ZDzZq"
            }
        }
        val mods = transaction { ServerMods.selectAll().where { ServerMods.serverId eq serverId.toKotlinUuid() }.toList() }
        assertEquals("fabric-api:Oa9ZDzZq", modrinthProjectsEnvVar(mods))
    }

    @Test
    fun `modrinthProjectsEnvVar serializes BETA correctly`() {
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        transaction {
            ServerMods.insert {
                it[ServerMods.serverId] = serverId.toKotlinUuid()
                it[ServerMods.modrinthProjectId] = "some-mod"
                it[ServerMods.displayName] = "Some Mod"
                it[ServerMods.pinStrategy] = "BETA"
            }
        }
        val mods = transaction { ServerMods.selectAll().where { ServerMods.serverId eq serverId.toKotlinUuid() }.toList() }
        assertEquals("some-mod:beta", modrinthProjectsEnvVar(mods))
    }

    @Test
    fun `modrinthProjectsEnvVar serializes ALPHA correctly`() {
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        transaction {
            ServerMods.insert {
                it[ServerMods.serverId] = serverId.toKotlinUuid()
                it[ServerMods.modrinthProjectId] = "some-mod"
                it[ServerMods.displayName] = "Some Mod"
                it[ServerMods.pinStrategy] = "ALPHA"
            }
        }
        val mods = transaction { ServerMods.selectAll().where { ServerMods.serverId eq serverId.toKotlinUuid() }.toList() }
        assertEquals("some-mod:alpha", modrinthProjectsEnvVar(mods))
    }

    @Test
    fun `modrinthProjectsEnvVar serializes multiple mods comma-separated`() {
        val nodeId = createNode()
        val serverId = createServer(nodeId)
        transaction {
            ServerMods.insert {
                it[ServerMods.serverId] = serverId.toKotlinUuid()
                it[ServerMods.modrinthProjectId] = "fabric-api"
                it[ServerMods.displayName] = "Fabric API"
                it[ServerMods.pinStrategy] = "LATEST"
            }
            ServerMods.insert {
                it[ServerMods.serverId] = serverId.toKotlinUuid()
                it[ServerMods.modrinthProjectId] = "sodium"
                it[ServerMods.displayName] = "Sodium"
                it[ServerMods.pinStrategy] = "PINNED"
                it[ServerMods.pinnedVersionId] = "abc123"
            }
        }
        val mods = transaction { ServerMods.selectAll().where { ServerMods.serverId eq serverId.toKotlinUuid() }.toList() }
        val result = modrinthProjectsEnvVar(mods)
        assert(result.contains("fabric-api")) { "Expected fabric-api in $result" }
        assert(result.contains("sodium:abc123")) { "Expected sodium:abc123 in $result" }
        assert(result.contains(",")) { "Expected comma separator in $result" }
    }
}
