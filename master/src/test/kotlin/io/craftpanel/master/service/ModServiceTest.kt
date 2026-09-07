package io.craftpanel.master.service

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.ServerMods
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.ModPinStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private fun mockClient(handler: MockRequestHandler): HttpClient = HttpClient(MockEngine) {
    engine { addHandler(handler) }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

class ModServiceTest :
    FunSpec({
        val repos = TestRepositories()

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
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
                it[Nodes.totalCpuShares] = 1024
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        fun createServer(nodeId: Uuid, serverType: String = "FABRIC", mcVersion: String = "1.21.5"): Uuid = transaction {
            Servers.insert {
                it[Servers.name] = "test-server"
                it[Servers.displayName] = "Test Server"
                it[Servers.nodeId] = nodeId
                it[Servers.serverType] = serverType
                it[Servers.mcVersion] = mcVersion
                it[Servers.memoryMb] = 1024
                it[Servers.hostPort] = 25565
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }

        fun addMod(serverId: Uuid, projectId: String, displayName: String) = transaction {
            ServerMods.insert {
                it[ServerMods.serverId] = EntityID(serverId, Servers)
                it[ServerMods.modrinthProjectId] = projectId
                it[ServerMods.displayName] = displayName
                it[ServerMods.pinStrategy] = "LATEST"
            }
        }

        test("addMod with LATEST strategy rejects a project with no compatible version") {
            val client = mockClient {
                respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
            val service = ModService(repos.serverRepository, repos.modRepository, client)
            val serverId = createServer(createNode())

            shouldThrow<UnprocessableException> {
                service.addMod(serverId, CreateModRequest(modrinthProjectId = "solstice-essentials", displayName = "Solstice Essentials", pinStrategy = ModPinStrategy.LATEST))
            }
        }

        test("addMod with LATEST strategy succeeds when a compatible version exists") {
            val client = mockClient {
                respond("""[{"id":"abc123"}]""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
            val service = ModService(repos.serverRepository, repos.modRepository, client)
            val serverId = createServer(createNode())

            val mod = service.addMod(serverId, CreateModRequest(modrinthProjectId = "fabric-api", displayName = "Fabric API", pinStrategy = ModPinStrategy.LATEST))
            mod.modrinthProjectId shouldBe "fabric-api"
        }

        test("addMod with PINNED strategy rejects a version id not present in Modrinth's response") {
            val client = mockClient {
                respond("""[{"id":"other-version"}]""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
            val service = ModService(repos.serverRepository, repos.modRepository, client)
            val serverId = createServer(createNode())

            shouldThrow<UnprocessableException> {
                service.addMod(
                    serverId,
                    CreateModRequest(modrinthProjectId = "fabric-api", displayName = "Fabric API", pinStrategy = ModPinStrategy.PINNED, pinnedVersionId = "does-not-exist")
                )
            }
        }

        test("addMod surfaces a BadGatewayException when Modrinth is unreachable") {
            val client = mockClient { throw java.io.IOException("boom") }
            val service = ModService(repos.serverRepository, repos.modRepository, client)
            val serverId = createServer(createNode())

            shouldThrow<BadGatewayException> {
                service.addMod(serverId, CreateModRequest(modrinthProjectId = "fabric-api", displayName = "Fabric API", pinStrategy = ModPinStrategy.LATEST))
            }
        }

        // ── Compatibility check ──────────────────────────────────────────────────

        test("checkCompatibility returns compatible=true with version details when Modrinth returns versions") {
            val client = mockClient {
                respond(
                    """[{"id":"v1","version_number":"0.119.0+1.22","version_type":"release"}]""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
            val service = ModService(repos.serverRepository, repos.modRepository, client)
            val serverId = createServer(createNode())
            addMod(serverId, "fabric-api", "Fabric API")

            val result = service.checkCompatibility(serverId, "1.22")
            result.targetVersion shouldBe "1.22"
            result.results.size shouldBe 1
            val r = result.results.first()
            r.modrinthProjectId shouldBe "fabric-api"
            r.displayName shouldBe "Fabric API"
            r.compatible shouldBe true
            r.latestCompatibleVersionId shouldBe "v1"
            r.latestCompatibleVersionNumber shouldBe "0.119.0+1.22"
        }

        test("checkCompatibility returns compatible=false when Modrinth returns empty") {
            val client = mockClient {
                respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
            val service = ModService(repos.serverRepository, repos.modRepository, client)
            val serverId = createServer(createNode())
            addMod(serverId, "solstice-essentials", "Solstice Essentials")

            val result = service.checkCompatibility(serverId, "1.22")
            result.results.size shouldBe 1
            val r = result.results.first()
            r.modrinthProjectId shouldBe "solstice-essentials"
            r.compatible shouldBe false
            r.latestCompatibleVersionId shouldBe null
            r.latestCompatibleVersionNumber shouldBe null
        }

        test("checkCompatibility picks latest release version over beta") {
            val client = mockClient {
                respond(
                    """[
                        {"id":"beta-v","version_number":"0.100.0-beta","version_type":"beta"},
                        {"id":"release-v","version_number":"0.99.0","version_type":"release"}
                    ]""".trimIndent(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
            val service = ModService(repos.serverRepository, repos.modRepository, client)
            val serverId = createServer(createNode())
            addMod(serverId, "fabric-api", "Fabric API")

            val result = service.checkCompatibility(serverId, "1.22")
            val r = result.results.first()
            r.compatible shouldBe true
            r.latestCompatibleVersionId shouldBe "release-v"
            r.latestCompatibleVersionNumber shouldBe "0.99.0"
        }

        test("checkCompatibility returns results for all mods") {
            val client = mockClient {
                respond(
                    """[{"id":"v1","version_number":"1.0","version_type":"release"}]""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
            val service = ModService(repos.serverRepository, repos.modRepository, client)
            val serverId = createServer(createNode())
            addMod(serverId, "fabric-api", "Fabric API")
            addMod(serverId, "sodium", "Sodium")

            val result = service.checkCompatibility(serverId, "1.22")
            result.results.size shouldBe 2
        }

        test("checkCompatibility throws BadGatewayException when Modrinth is unreachable") {
            val client = mockClient { throw java.io.IOException("boom") }
            val service = ModService(repos.serverRepository, repos.modRepository, client)
            val serverId = createServer(createNode())
            addMod(serverId, "fabric-api", "Fabric API")

            shouldThrow<BadGatewayException> {
                service.checkCompatibility(serverId, "1.22")
            }
        }
    })
