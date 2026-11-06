package io.craftpanel.master.routes

import io.craftpanel.master.*
import io.craftpanel.master.auth.*
import io.craftpanel.master.config.JwtConfig
import io.craftpanel.master.database.schema.*
import io.craftpanel.master.service.ClientPackService
import io.craftpanel.master.service.ModService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.uuid.Uuid

private fun clientPackHttpClient(): HttpClient = HttpClient(MockEngine) {
    engine {
        addHandler { request ->
            val url = request.url.toString()
            val body = when {
                url.startsWith("https://meta.fabricmc.net/v2/versions/loader/") ->
                    """[{"loader": {"version": "0.20.1", "stable": true}}]"""

                url.contains("/project/sodium/version") ->
                    """[{"id": "sodium-v1", "project_id": "sodium", "version_number": "mc1.21.1-0.8.13",
                        "version_type": "release", "environment": "client_only",
                        "files": [{"hashes": {"sha1": "${"a".repeat(40)}", "sha512": "${"b".repeat(128)}"},
                                   "url": "https://cdn.modrinth.com/data/sodium/versions/sodium-v1/sodium.jar",
                                   "filename": "sodium.jar", "primary": true, "size": 42, "file_type": null}]}]"""

                url.contains("/project/chunky/version") ->
                    """[{"id": "chunky-v1", "project_id": "chunky", "version_number": "1.4.36",
                        "version_type": "release", "environment": "server_only",
                        "files": [{"hashes": {"sha1": "${"c".repeat(40)}", "sha512": "${"d".repeat(128)}"},
                                   "url": "https://cdn.modrinth.com/data/chunky/versions/chunky-v1/chunky.jar",
                                   "filename": "chunky.jar", "primary": true, "size": 42, "file_type": null}]}]"""

                else -> error("Unexpected request: $url")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

class ClientPackRoutesTest :
    FunSpec({
        val jwtConfig = JwtConfig(
            secret = "test-secret-that-is-at-least-32-characters!!",
            issuer = "craftpanel-test",
            audience = "craftpanel-test",
            expirySeconds = 900
        )
        val jwtManager = JwtManager(jwtConfig)
        val repos = TestRepositories()

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        fun Route.configureRoutes() {
            modsRoutes(
                ModService(modRepository = repos.modRepository, serverRepository = repos.serverRepository, client = clientPackHttpClient()),
                testServerSpecSync(repos),
                ClientPackService(
                    serverRepository = repos.serverRepository,
                    modRepository = repos.modRepository,
                    envVarsRepository = repos.envVarsRepository,
                    client = clientPackHttpClient()
                )
            )
        }

        fun createUser(email: String = "admin@example.com"): Uuid = transaction {
            Users.insert {
                it[username] = email.substringBefore("@")
                it[Users.email] = email
                it[passwordHash] = Argon2Hasher.hash("hunter2")
                it[isActive] = true
            }[Users.id].let { Uuid.parse(it.toString()) }
        }

        fun assignGlobalGroup(userId: Uuid, groupName: String) = transaction {
            val groupId = Groups.selectAll()
                .where { Groups.name eq groupName }
                .first()[Groups.id]
            UserGroupAssignments.insert {
                it[UserGroupAssignments.userId] = userId
                it[UserGroupAssignments.groupId] = groupId
                it[scopeType] = "GLOBAL"
            }
        }

        fun tokenFor(userId: Uuid): String = jwtManager.generate(TokenClaims(userId = userId, name = "admin", email = "admin@example.com", groups = emptyList()))

        fun createServer(serverType: String = "FABRIC"): Uuid = transaction {
            val nodeId = Nodes.insert {
                it[hostname] = "node-1"
                it[displayName] = "node-1"
                it[publicIp] = "1.2.3.4"
                it[privateIp] = "10.0.0.1"
                it[tokenHash] = "a".repeat(64)
                it[status] = "ACTIVE"
                it[totalRamMb] = 8192
                it[totalCpuMillicores] = 1024
            }[Nodes.id].let { Uuid.parse(it.toString()) }
            Servers.insert {
                it[name] = "test-server"
                it[displayName] = "Test Server"
                it[Servers.nodeId] = EntityID(nodeId, Nodes)
                it[Servers.serverType] = serverType
                it[Servers.mcVersion] = "1.21.1"
                it[memoryMb] = 1024
                it[hostPort] = 25565
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }

        fun addMod(serverId: Uuid, projectId: String, displayName: String) = transaction {
            ServerMods.insert {
                it[ServerMods.serverId] = EntityID(serverId, Servers)
                it[modrinthProjectId] = projectId
                it[ServerMods.displayName] = displayName
                it[pinStrategy] = "LATEST"
            }
        }

        test("preview requires server view permission") {
            testApplication {
                testApp { _ -> configureRoutes() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")
                val serverId = createServer()
                addMod(serverId, "sodium", "Sodium")
                addMod(serverId, "chunky", "Chunky")

                val res = client.get("/api/servers/$serverId/mods/client-pack/preview") {
                    header("Authorization", "Bearer ${tokenFor(userId)}")
                }

                res.status shouldBe HttpStatusCode.OK
                val body = res.body<JsonObject>()
                body["loader"]!!.jsonPrimitive.content shouldBe "fabric"
                body["loader_version"]!!.jsonPrimitive.content shouldBe "0.20.1"
                body["mc_version"]!!.jsonPrimitive.content shouldBe "1.21.1"
                body["mods"]!!.jsonArray.map { it.jsonObject["project_id"]!!.jsonPrimitive.content } shouldBe listOf("sodium")
                body["skipped"]!!.jsonArray.map { it.jsonObject["project_id"]!!.jsonPrimitive.content } shouldBe listOf("chunky")
            }
        }

        test("download serves a Prism-compatible mrpack") {
            testApplication {
                testApp { _ -> configureRoutes() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")
                val serverId = createServer()
                addMod(serverId, "sodium", "Sodium")

                val res = client.get("/api/servers/$serverId/mods/client-pack") {
                    header("Authorization", "Bearer ${tokenFor(userId)}")
                }

                res.status shouldBe HttpStatusCode.OK
                res.contentType() shouldBe ContentType("application", "x-modrinth-modpack+zip")
                res.headers[HttpHeaders.ContentDisposition]!! shouldContain "test-server.mrpack"

                val zip = ZipInputStream(ByteArrayInputStream(res.readRawBytes()))
                val entry = generateSequence { zip.nextEntry }.firstOrNull { it.name == "modrinth.index.json" }
                    ?: error("modrinth.index.json missing from archive")
                entry.name shouldBe "modrinth.index.json"
                val index = Json.parseToJsonElement(zip.readBytes().toString(Charsets.UTF_8)).jsonObject
                index["dependencies"]!!.jsonObject["fabric-loader"]!!.jsonPrimitive.content shouldBe "0.20.1"
                index["files"]!!.jsonArray.size shouldBe 1
                index["files"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content shouldBe "mods/sodium.jar"
            }
        }

        test("preview rejects non-mod-loader servers") {
            testApplication {
                testApp { _ -> configureRoutes() }
                val client = jsonClient()
                val userId = createUser()
                assignGlobalGroup(userId, "Viewer")
                val serverId = createServer(serverType = "PAPER")
                addMod(serverId, "sodium", "Sodium")

                val res = client.get("/api/servers/$serverId/mods/client-pack/preview") {
                    header("Authorization", "Bearer ${tokenFor(userId)}")
                }

                res.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }
    })
