package io.craftpanel.master.routes

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.auth.JwtManager
import io.craftpanel.master.auth.TokenClaims
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.jsonClient
import io.craftpanel.master.service.ModService
import io.craftpanel.master.testApp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ModsRoutesTest :
    FunSpec({
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

        val repos = TestRepositories()

        fun Route.configureModsTest() {
            modsRoutes(ModService(modRepository = repos.modRepository, serverRepository = repos.serverRepository))
        }

        fun createUser(email: String = "admin@example.com"): Uuid = transaction {
            Users.insert {
                it[Users.username] = email.substringBefore("@")
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

        fun tokenFor(userId: Uuid): String = jwtManager.generate(TokenClaims(userId = userId, name = "admin", email = "admin@example.com", groups = emptyList()))

        fun createNode(): Uuid = transaction {
            Nodes.insert {
                it[Nodes.hostname] = "node-1"
                it[Nodes.displayName] = "node-1"
                it[Nodes.publicIp] = "1.2.3.4"
                it[Nodes.privateIp] = "10.0.0.1"
                it[Nodes.tokenHash] = "a".repeat(64)
                it[Nodes.status] = "ACTIVE"
                it[Nodes.totalRamMb] = 8192
                it[Nodes.totalCpuShares] = 1024
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        fun createServer(nodeId: Uuid): Uuid = transaction {
            Servers.insert {
                it[Servers.name] = "test-server"
                it[Servers.displayName] = "Test Server"
                it[Servers.nodeId] = nodeId
                it[Servers.serverType] = "VANILLA"
                it[Servers.mcVersion] = "LATEST"
                it[Servers.memoryMb] = 1024
                it[Servers.hostPort] = 25565
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }

        // ── List mods ─────────────────────────────────────────────────────────────

        test("list mods returns empty list when none exist") {
            testApplication {
                testApp { _ -> configureModsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val token = tokenFor(userId)
                val nodeId = createNode()
                val serverId = createServer(nodeId)

                val res = client.get("/api/servers/$serverId/mods") { header("Authorization", "Bearer $token") }
                res.status shouldBe HttpStatusCode.OK
                res.body<JsonObject>()["mods"]!!.jsonArray.size shouldBe 0
            }
        }

        test("list mods requires server_mods permission") {
            testApplication {
                testApp { _ -> configureModsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")
                val token = tokenFor(userId)
                val nodeId = createNode()
                val serverId = createServer(nodeId)

                val res = client.get("/api/servers/$serverId/mods") { header("Authorization", "Bearer $token") }
                res.status shouldBe HttpStatusCode.Forbidden
            }
        }

        // ── Add mod ───────────────────────────────────────────────────────────────

        test("add mod with LATEST strategy succeeds") {
            testApplication {
                testApp { _ -> configureModsTest() }
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
                res.status shouldBe HttpStatusCode.Created
                val body = res.body<JsonObject>()
                body["modrinth_project_id"]!!.jsonPrimitive.content shouldBe "fabric-api"
                body["pin_strategy"]!!.jsonPrimitive.content shouldBe "LATEST"
            }
        }

        test("add mod with PINNED strategy requires pinned_version_id") {
            testApplication {
                testApp { _ -> configureModsTest() }
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
                res.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        test("add mod with PINNED strategy and version succeeds") {
            testApplication {
                testApp { _ -> configureModsTest() }
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
                res.status shouldBe HttpStatusCode.Created
                val body = res.body<JsonObject>()
                body["pinned_version_id"]!!.jsonPrimitive.content shouldBe "Oa9ZDzZq"
            }
        }

        test("add mod with BETA strategy succeeds") {
            testApplication {
                testApp { _ -> configureModsTest() }
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
                res.status shouldBe HttpStatusCode.Created
                res.body<JsonObject>()["pin_strategy"]!!.jsonPrimitive.content shouldBe "BETA"
            }
        }

        test("add mod with ALPHA strategy succeeds") {
            testApplication {
                testApp { _ -> configureModsTest() }
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
                res.status shouldBe HttpStatusCode.Created
                res.body<JsonObject>()["pin_strategy"]!!.jsonPrimitive.content shouldBe "ALPHA"
            }
        }

        test("add duplicate mod returns 409") {
            testApplication {
                testApp { _ -> configureModsTest() }
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
                res.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("add mod with invalid pin_strategy returns 400") {
            testApplication {
                testApp { _ -> configureModsTest() }
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
                // Invalid enum value fails kotlinx deserialization → 400 (was a manual 422 check before pin_strategy became an enum).
                res.status shouldBe HttpStatusCode.BadRequest
            }
        }

        // ── Patch mod ─────────────────────────────────────────────────────────────

        test("patch mod updates pin_strategy") {
            testApplication {
                testApp { _ -> configureModsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val token = tokenFor(userId)
                val nodeId = createNode()
                val serverId = createServer(nodeId)

                val modId = transaction {
                    ServerMods.insert {
                        it[ServerMods.serverId] = serverId
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
                res.status shouldBe HttpStatusCode.OK
                val body = res.body<JsonObject>()
                body["pin_strategy"]!!.jsonPrimitive.content shouldBe "PINNED"
                body["pinned_version_id"]!!.jsonPrimitive.content shouldBe "Oa9ZDzZq"
            }
        }

        // ── Delete mod ────────────────────────────────────────────────────────────

        test("delete mod removes it") {
            testApplication {
                testApp { _ -> configureModsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val token = tokenFor(userId)
                val nodeId = createNode()
                val serverId = createServer(nodeId)

                val modId = transaction {
                    ServerMods.insert {
                        it[ServerMods.serverId] = serverId
                        it[ServerMods.modrinthProjectId] = "fabric-api"
                        it[ServerMods.displayName] = "Fabric API"
                        it[ServerMods.pinStrategy] = "LATEST"
                    }[ServerMods.id]
                }

                val res = client.delete("/api/servers/$serverId/mods/$modId") { header("Authorization", "Bearer $token") }
                res.status shouldBe HttpStatusCode.NoContent
                transaction {
                    ServerMods.selectAll()
                        .where { ServerMods.id eq modId }
                        .count() shouldBe 0
                }
            }
        }

        test("delete nonexistent mod returns 404") {
            testApplication {
                testApp { _ -> configureModsTest() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Super Admin")
                val token = tokenFor(userId)
                val nodeId = createNode()
                val serverId = createServer(nodeId)

                val res = client.delete("/api/servers/$serverId/mods/${Uuid.random()}") {
                    header("Authorization", "Bearer $token")
                }
                res.status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── MODRINTH_PROJECTS serialization ───────────────────────────────────────

        test("modrinthProjectsEnvVar serializes LATEST correctly") {
            val nodeId = createNode()
            val serverId = createServer(nodeId)
            transaction {
                ServerMods.insert {
                    it[ServerMods.serverId] = serverId
                    it[ServerMods.modrinthProjectId] = "fabric-api"
                    it[ServerMods.displayName] = "Fabric API"
                    it[ServerMods.pinStrategy] = "LATEST"
                }
            }
            ModService(modRepository = repos.modRepository, serverRepository = repos.serverRepository).buildModrinthEnvVar(serverId) shouldBe "fabric-api"
        }

        test("modrinthProjectsEnvVar serializes PINNED correctly") {
            val nodeId = createNode()
            val serverId = createServer(nodeId)
            transaction {
                ServerMods.insert {
                    it[ServerMods.serverId] = serverId
                    it[ServerMods.modrinthProjectId] = "fabric-api"
                    it[ServerMods.displayName] = "Fabric API"
                    it[ServerMods.pinStrategy] = "PINNED"
                    it[ServerMods.pinnedVersionId] = "Oa9ZDzZq"
                }
            }
            ModService(modRepository = repos.modRepository, serverRepository = repos.serverRepository).buildModrinthEnvVar(serverId) shouldBe "fabric-api:Oa9ZDzZq"
        }

        test("modrinthProjectsEnvVar serializes BETA correctly") {
            val nodeId = createNode()
            val serverId = createServer(nodeId)
            transaction {
                ServerMods.insert {
                    it[ServerMods.serverId] = serverId
                    it[ServerMods.modrinthProjectId] = "some-mod"
                    it[ServerMods.displayName] = "Some Mod"
                    it[ServerMods.pinStrategy] = "BETA"
                }
            }
            ModService(modRepository = repos.modRepository, serverRepository = repos.serverRepository).buildModrinthEnvVar(serverId) shouldBe "some-mod:beta"
        }

        test("modrinthProjectsEnvVar serializes ALPHA correctly") {
            val nodeId = createNode()
            val serverId = createServer(nodeId)
            transaction {
                ServerMods.insert {
                    it[ServerMods.serverId] = serverId
                    it[ServerMods.modrinthProjectId] = "some-mod"
                    it[ServerMods.displayName] = "Some Mod"
                    it[ServerMods.pinStrategy] = "ALPHA"
                }
            }
            ModService(modRepository = repos.modRepository, serverRepository = repos.serverRepository).buildModrinthEnvVar(serverId) shouldBe "some-mod:alpha"
        }

        test("modrinthProjectsEnvVar serializes multiple mods comma-separated") {
            val nodeId = createNode()
            val serverId = createServer(nodeId)
            transaction {
                ServerMods.insert {
                    it[ServerMods.serverId] = serverId
                    it[ServerMods.modrinthProjectId] = "fabric-api"
                    it[ServerMods.displayName] = "Fabric API"
                    it[ServerMods.pinStrategy] = "LATEST"
                }
                ServerMods.insert {
                    it[ServerMods.serverId] = serverId
                    it[ServerMods.modrinthProjectId] = "sodium"
                    it[ServerMods.displayName] = "Sodium"
                    it[ServerMods.pinStrategy] = "PINNED"
                    it[ServerMods.pinnedVersionId] = "abc123"
                }
            }
            val result = ModService(modRepository = repos.modRepository, serverRepository = repos.serverRepository).buildModrinthEnvVar(serverId)
            result.contains("fabric-api") shouldBe true
            result.contains("sodium:abc123") shouldBe true
            result.contains(",") shouldBe true
        }
    })
