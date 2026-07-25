package io.craftpanel.master.service

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.schema.Nodes
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
    })
