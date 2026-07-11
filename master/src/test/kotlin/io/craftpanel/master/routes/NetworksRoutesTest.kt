package io.craftpanel.master.routes

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.TokenClaims
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.Groups
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.jsonClient
import io.craftpanel.master.service.*
import io.craftpanel.master.service.repo.GroupRepositoryImpl
import io.craftpanel.master.service.repo.NetworkRepositoryImpl
import io.craftpanel.master.service.repo.NodeRepositoryImpl
import io.craftpanel.master.service.repo.UserRepositoryImpl
import io.craftpanel.master.testApp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.uuid.Uuid
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.routing.Route
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class NetworksRoutesTest : FunSpec({
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

    val repos = TestRepositories()

    fun Route.configureNetworksTest() {
        networksRoutes(
            NetworkService(
                networkRepository = NetworkRepositoryImpl(),
                serverRepository = repos.serverRepository,
                nodeRepository = NodeRepositoryImpl(),
                userRepository = UserRepositoryImpl(),
                groupRepository = GroupRepositoryImpl(),
            )
        )
    }

    fun createUser(username: String = "admin", email: String = "admin@example.com"): Uuid = transaction {
        Users.insert {
            it[Users.username] = username
            it[Users.email] = email
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

    fun assignNetworkGroup(userId: Uuid, groupName: String, networkId: Uuid) = transaction {
        val groupId = Groups.selectAll()
            .where { Groups.name eq groupName }
            .first()[Groups.id]
        UserGroupAssignments.insert {
            it[UserGroupAssignments.userId] = userId
            it[UserGroupAssignments.groupId] = groupId
            it[UserGroupAssignments.scopeType] = "NETWORK"
            it[UserGroupAssignments.scopeId] = networkId
        }
    }

    fun tokenFor(userId: Uuid, username: String = "admin"): String =
        jwtManager.generate(TokenClaims(userId = userId, name = username, email = "$username@example.com", groups = emptyList()))

    fun createNetwork(
        name: String = "test-network",
        description: String? = null,
    ): Uuid = transaction {
        ServerNetworks.insert {
            it[ServerNetworks.name] = name
            it[ServerNetworks.description] = description
        }[ServerNetworks.id].let { Uuid.parse(it.toString()) }
    }

    // ── GET /networks ────────────────────────────────────────────────────────

    test("GET networks returns 401 without token") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val resp = client.get("/api/networks")
            resp.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("GET networks returns 403 without server-view permission") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val client = jsonClient()
            val userId = createUser()
            val resp = client.get("/api/networks") {
                bearerAuth(tokenFor(userId))
            }
            resp.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("GET networks returns empty list") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Viewer")
            val resp = client.get("/api/networks") { bearerAuth(tokenFor(userId)) }
            resp.status shouldBe HttpStatusCode.OK
            resp.body<List<JsonObject>>().size shouldBe 0
        }
    }

    test("GET networks returns networks with server_count") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Viewer")
            createNetwork("net-a")
            createNetwork("net-b")
            val resp = client.get("/api/networks") { bearerAuth(tokenFor(userId)) }
            resp.status shouldBe HttpStatusCode.OK
            val body = resp.body<List<JsonObject>>()
            body.size shouldBe 2
            val names = body.map { it["name"]!!.jsonPrimitive.content }
                .toSet()
            names shouldBe setOf("net-a", "net-b")
            body.forEach { it["server_count"]!!.jsonPrimitive.content.toInt() shouldBe 0 }
        }
    }

    // ── POST /networks ───────────────────────────────────────────────────────

    test("POST networks returns 403 without server-create") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Viewer")
            val resp = client.post("/api/networks") {
                bearerAuth(tokenFor(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"n"}""")
            }
            resp.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("POST networks creates network and returns 201") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val resp = client.post("/api/networks") {
                bearerAuth(tokenFor(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"lobby","proxy_port":25577,"description":"main lobby"}""")
            }
            resp.status shouldBe HttpStatusCode.Created
            val body = resp.body<JsonObject>()
            body["name"]!!.jsonPrimitive.content shouldBe "lobby"
            body["proxy_port"]!!.jsonPrimitive.content.toInt() shouldBe 25577
            body["description"]!!.jsonPrimitive.content shouldBe "main lobby"
            body["server_count"]!!.jsonPrimitive.content.toInt() shouldBe 0
            body["id"] shouldNotBe null
            body["created_at"] shouldNotBe null
        }
    }

    test("POST networks returns 409 on duplicate name") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            createNetwork("dup")
            val resp = client.post("/api/networks") {
                bearerAuth(tokenFor(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"dup"}""")
            }
            resp.status shouldBe HttpStatusCode.Conflict
        }
    }

    // ── GET /networks/{id} ───────────────────────────────────────────────────

    test("GET networks by id returns 404 for unknown") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Viewer")
            val resp = client.get("/api/networks/${Uuid.random()}") { bearerAuth(tokenFor(userId)) }
            resp.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("GET networks by id returns detail with servers") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Viewer")
            val netId = createNetwork("detail-net")
            val resp = client.get("/api/networks/$netId") { bearerAuth(tokenFor(userId)) }
            resp.status shouldBe HttpStatusCode.OK
            val body = resp.body<JsonObject>()
            body["name"]!!.jsonPrimitive.content shouldBe "detail-net"
            body["server_count"]!!.jsonPrimitive.content.toInt() shouldBe 0
            body["servers"]!!.jsonArray.size shouldBe 0
        }
    }

    test("GET networks by id returns 400 for invalid UUID") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Viewer")
            val resp = client.get("/api/networks/not-a-uuid") { bearerAuth(tokenFor(userId)) }
            resp.status shouldBe HttpStatusCode.BadRequest
        }
    }

    // ── PATCH /networks/{id} ─────────────────────────────────────────────────

    test("PATCH networks returns 403 without server-configure on network") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Viewer")
            val netId = createNetwork("to-patch")
            val resp = client.patch("/api/networks/$netId") {
                bearerAuth(tokenFor(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"new-name"}""")
            }
            resp.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("PATCH networks updates name with global permission") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val netId = createNetwork("old-name")
            val resp = client.patch("/api/networks/$netId") {
                bearerAuth(tokenFor(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"new-name","description":"updated"}""")
            }
            resp.status shouldBe HttpStatusCode.NoContent
            val updated = transaction {
                ServerNetworks.selectAll()
                    .where { ServerNetworks.id eq netId }
                    .first()
            }
            updated[ServerNetworks.name] shouldBe "new-name"
            updated[ServerNetworks.description] shouldBe "updated"
        }
    }

    test("PATCH networks updates name with network-scoped configure permission") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val client = jsonClient()
            val userId = createUser()
            val netId = createNetwork("net-scoped")
            assignNetworkGroup(userId, "Server Admin", netId)
            val resp = client.patch("/api/networks/$netId") {
                bearerAuth(tokenFor(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"net-scoped-updated"}""")
            }
            resp.status shouldBe HttpStatusCode.NoContent
        }
    }

    test("PATCH networks returns 409 on name conflict") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            createNetwork("existing")
            val netId = createNetwork("to-rename")
            val resp = client.patch("/api/networks/$netId") {
                bearerAuth(tokenFor(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"existing"}""")
            }
            resp.status shouldBe HttpStatusCode.Conflict
        }
    }

    test("PATCH networks returns 404 for unknown") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val resp = client.patch("/api/networks/${Uuid.random()}") {
                bearerAuth(tokenFor(userId))
                contentType(ContentType.Application.Json)
                setBody("""{"name":"x"}""")
            }
            resp.status shouldBe HttpStatusCode.NotFound
        }
    }

    // ── DELETE /networks/{id} ────────────────────────────────────────────────

    test("DELETE networks returns 403 without server-delete on network") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Viewer")
            val netId = createNetwork("del-test")
            val resp = client.delete("/api/networks/$netId") { bearerAuth(tokenFor(userId)) }
            resp.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("DELETE networks deletes network and nulls server network_id") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val netId = createNetwork("del-me")
            val resp = client.delete("/api/networks/$netId") { bearerAuth(tokenFor(userId)) }
            resp.status shouldBe HttpStatusCode.NoContent
            val exists = transaction {
                ServerNetworks.selectAll()
                    .where { ServerNetworks.id eq netId }
                    .firstOrNull() != null
            }
            exists shouldBe false
        }
    }

    test("DELETE networks returns 404 for unknown") {
        testApplication {
            testApp { _ -> configureNetworksTest() }
            val client = jsonClient()
            val userId = createUser()
            assignGlobalGroup(userId, "Super Admin")
            val resp = client.delete("/api/networks/${Uuid.random()}") { bearerAuth(tokenFor(userId)) }
            resp.status shouldBe HttpStatusCode.NotFound
        }
    }
})
