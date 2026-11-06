package io.craftpanel.master.service

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.database.schema.ServerMods
import io.craftpanel.master.database.schema.Servers
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.uuid.Uuid

private fun packClient(handler: MockRequestHandler): HttpClient = HttpClient(MockEngine) {
    engine { addHandler(handler) }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

private fun versionJson(
    id: String,
    projectId: String,
    versionNumber: String,
    versionType: String,
    environment: String,
    fileName: String,
    sha1: String = "a".repeat(40),
    sha512: String = "b".repeat(128)
): String = """
    [{
      "id": "$id",
      "project_id": "$projectId",
      "version_number": "$versionNumber",
      "version_type": "$versionType",
      "environment": "$environment",
      "files": [{
        "hashes": {"sha1": "$sha1", "sha512": "$sha512"},
        "url": "https://cdn.modrinth.com/data/$projectId/versions/$id/$fileName",
        "filename": "$fileName",
        "primary": true,
        "size": 1234,
        "file_type": null
      }]
    }]
""".trimIndent()

private const val FABRIC_LOADERS = """
[
  {"loader": {"version": "0.20.1", "stable": true}},
  {"loader": {"version": "0.20.0", "stable": true}},
  {"loader": {"version": "0.21.0-beta.1", "stable": false}}
]
"""

private fun modrinthHandler(): MockRequestHandler = { request ->
    val url = request.url.toString()
    val body = when {
        url.startsWith("https://meta.fabricmc.net/v2/versions/loader/") -> FABRIC_LOADERS

        url.startsWith("https://meta.quiltmc.org/v3/versions/loader/") ->
            """[{"loader": {"version": "0.20.0-beta.9"}}, {"loader": {"version": "0.24.0"}}, {"loader": {"version": "0.30.1"}}]"""

        url.startsWith("https://files.minecraftforge.net/") ->
            """{"promos": {"1.21.1-recommended": "52.1.20", "1.21.1-latest": "52.1.21"}}"""

        url.startsWith("https://maven.neoforged.net/") -> """
            <metadata>
              <versioning>
                <versions>
                  <version>21.1.200</version>
                  <version>21.1.265</version>
                  <version>21.1.266-beta</version>
                  <version>21.2.1</version>
                </versions>
              </versioning>
            </metadata>
        """.trimIndent()

        url.startsWith("https://api.modrinth.com/v2/projects?") -> "[]"

        url.contains("/project/sodium/version") ->
            versionJson("sodium-v1", "sodium", "mc1.21.1-0.8.13", "release", "client_only", "sodium-fabric-0.8.13+mc1.21.1.jar")

        url.contains("/project/fabric-api/version") ->
            versionJson("fapi-v1", "fabric-api", "0.165.4+1.21.1", "release", "client_or_server_prefers_both", "fabric-api-0.165.4+1.21.1.jar")

        url.contains("/project/lithium/version") ->
            versionJson("lithium-v1", "lithium", "mc1.21.1-0.15.3", "release", "client_and_server", "lithium-fabric-mc1.21.1-0.15.3.jar")

        url.contains("/project/chunky/version") ->
            versionJson("chunky-v1", "chunky", "1.4.36", "release", "server_only", "chunky-fabric-1.4.36.jar")

        url.contains("/project/polymer/version") ->
            versionJson("polymer-v1", "polymer", "0.9.18", "release", "server_only_client_optional", "polymer-0.9.18.jar")

        url.contains("/project/singleplayer/version") ->
            versionJson("sp-v1", "singleplayer", "1.0.0", "release", "singleplayer_only", "singleplayer-1.0.0.jar")

        url.contains("/project/pinned/version") ->
            """
            [
              {"id": "newer", "project_id": "pinned", "version_number": "2.0.0", "version_type": "release",
               "environment": "client_and_server",
               "files": [{"hashes": {"sha1": "${"c".repeat(40)}", "sha512": "${"d".repeat(128)}"},
                          "url": "https://cdn.modrinth.com/data/pinned/versions/newer/pinned-2.0.0.jar",
                          "filename": "pinned-2.0.0.jar", "primary": true, "size": 10, "file_type": null}]},
              {"id": "older", "project_id": "pinned", "version_number": "1.0.0", "version_type": "release",
               "environment": "client_and_server",
               "files": [{"hashes": {"sha1": "${"e".repeat(40)}", "sha512": "${"f".repeat(128)}"},
                          "url": "https://cdn.modrinth.com/data/pinned/versions/older/pinned-1.0.0.jar",
                          "filename": "pinned-1.0.0.jar", "primary": true, "size": 10, "file_type": null}]}
            ]
            """.trimIndent()

        else -> error("Unexpected request: $url")
    }
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
}

class ClientPackServiceTest :
    FunSpec({
        val repos = TestRepositories()

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        fun createNode(): Uuid = transaction {
            Nodes.insert {
                it[hostname] = "node-1"
                it[displayName] = "node-1"
                it[publicIp] = "1.2.3.4"
                it[privateIp] = "10.0.0.1"
                it[tokenHash] = "a".repeat(64)
                it[status] = "ACTIVE"
                it[totalRamMb] = 8192
                it[totalCpuMillicores] = 1024
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        fun createServer(nodeId: Uuid, serverType: String = "FABRIC", mcVersion: String = "1.21.1"): Uuid = transaction {
            Servers.insert {
                it[name] = "test-server"
                it[displayName] = "Test Server"
                it[Servers.nodeId] = EntityID(nodeId, Nodes)
                it[Servers.serverType] = serverType
                it[Servers.mcVersion] = mcVersion
                it[memoryMb] = 1024
                it[hostPort] = 25565
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }

        fun addMod(serverId: Uuid, projectId: String, displayName: String, pinStrategy: String = "LATEST", pinnedVersionId: String? = null) = transaction {
            ServerMods.insert {
                it[ServerMods.serverId] = EntityID(serverId, Servers)
                it[modrinthProjectId] = projectId
                it[ServerMods.displayName] = displayName
                it[ServerMods.pinStrategy] = pinStrategy
                it[ServerMods.pinnedVersionId] = pinnedVersionId
            }
        }

        fun service() = ClientPackService(
            serverRepository = repos.serverRepository,
            modRepository = repos.modRepository,
            envVarsRepository = repos.envVarsRepository,
            client = packClient(modrinthHandler())
        )

        fun indexOf(bytes: ByteArray): Map<String, kotlinx.serialization.json.JsonElement> {
            val zip = ZipInputStream(ByteArrayInputStream(bytes))
            val entry = generateSequence { zip.nextEntry }.firstOrNull { it.name == "modrinth.index.json" }
            entry.shouldNotBeNull()
            val text = zip.readBytes().toString(Charsets.UTF_8)
            return Json.parseToJsonElement(text).jsonObject
        }

        test("includes only client-required mods and skips server-side ones") {
            val serverId = createServer(createNode())
            addMod(serverId, "sodium", "Sodium")
            addMod(serverId, "fabric-api", "Fabric API")
            addMod(serverId, "lithium", "Lithium")
            addMod(serverId, "chunky", "Chunky")
            addMod(serverId, "polymer", "Polymer")
            addMod(serverId, "singleplayer", "LAN Properties")

            val plan = service().preview(serverId)

            plan.mods.map { it.projectId } shouldContainExactlyInAnyOrder listOf("sodium", "fabric-api", "lithium")
            plan.skipped.map { it.projectId } shouldContainExactlyInAnyOrder listOf("chunky", "polymer", "singleplayer")
            plan.loader shouldBe "fabric"
            plan.loaderVersion shouldBe "0.20.1"
            plan.mcVersion shouldBe "1.21.1"
            plan.packFileName shouldBe "test-server.mrpack"
        }

        test("renders a valid mrpack index with hashes, env and dependencies") {
            val serverId = createServer(createNode())
            addMod(serverId, "sodium", "Sodium")
            addMod(serverId, "lithium", "Lithium")

            val bundle = service().build(serverId)
            bundle.fileName shouldBe "test-server.mrpack"
            val index = indexOf(bundle.bytes)

            index["formatVersion"]!!.jsonPrimitive.content shouldBe "1"
            index["game"]!!.jsonPrimitive.content shouldBe "minecraft"
            val dependencies = index["dependencies"]!!.jsonObject
            dependencies["minecraft"]!!.jsonPrimitive.content shouldBe "1.21.1"
            dependencies["fabric-loader"]!!.jsonPrimitive.content shouldBe "0.20.1"

            val files = index["files"]!!.jsonArray
            files.size shouldBe 2
            val sodium = files.map { it.jsonObject }.first { it["path"]!!.jsonPrimitive.content.contains("sodium") }
            sodium["hashes"]!!.jsonObject["sha1"]!!.jsonPrimitive.content shouldBe "a".repeat(40)
            sodium["hashes"]!!.jsonObject["sha512"]!!.jsonPrimitive.content shouldBe "b".repeat(128)
            sodium["env"]!!.jsonObject["client"]!!.jsonPrimitive.content shouldBe "required"
            sodium["env"]!!.jsonObject["server"]!!.jsonPrimitive.content shouldBe "unsupported"
            sodium["downloads"]!!.jsonArray.size shouldBe 1
            sodium["fileSize"]!!.jsonPrimitive.content shouldBe "1234"

            val lithium = files.map { it.jsonObject }.first { it["path"]!!.jsonPrimitive.content.contains("lithium") }
            lithium["env"]!!.jsonObject["client"]!!.jsonPrimitive.content shouldBe "required"
            lithium["env"]!!.jsonObject["server"]!!.jsonPrimitive.content shouldBe "required"
        }

        test("PINNED resolves the exact pinned version") {
            val serverId = createServer(createNode())
            addMod(serverId, "pinned", "Pinned Mod", pinStrategy = "PINNED", pinnedVersionId = "older")

            val plan = service().preview(serverId)

            plan.mods.single().versionId shouldBe "older"
            plan.mods.single().versionNumber shouldBe "1.0.0"
        }

        test("uses a pinned loader version from the server env vars") {
            val serverId = createServer(createNode())
            addMod(serverId, "sodium", "Sodium")
            transaction {
                ServerEnvVars.insert {
                    it[ServerEnvVars.serverId] = EntityID(serverId, Servers)
                    it[key] = "FABRIC_LOADER_VERSION"
                    it[value] = "0.16.10"
                }
            }

            service().preview(serverId).loaderVersion shouldBe "0.16.10"
        }

        test("resolves the highest stable Quilt loader") {
            val serverId = createServer(createNode(), serverType = "QUILT")
            addMod(serverId, "sodium", "Sodium")

            val plan = service().preview(serverId)

            plan.loader shouldBe "quilt"
            plan.loaderVersion shouldBe "0.30.1"
        }

        test("resolves the recommended Forge version") {
            val serverId = createServer(createNode(), serverType = "FORGE")
            addMod(serverId, "sodium", "Sodium")

            service().preview(serverId).loaderVersion shouldBe "52.1.20"
        }

        test("resolves the newest NeoForge build for the Minecraft line") {
            val serverId = createServer(createNode(), serverType = "NEOFORGE")
            addMod(serverId, "sodium", "Sodium")

            service().preview(serverId).loaderVersion shouldBe "21.1.265"
        }

        test("rejects plugin server types") {
            val serverId = createServer(createNode(), serverType = "PAPER")
            addMod(serverId, "sodium", "Sodium")

            val error = shouldThrow<UnprocessableException> { service().preview(serverId) }
            error.message shouldContain "only available"
        }

        test("rejects an empty mod list") {
            val serverId = createServer(createNode())

            val error = shouldThrow<UnprocessableException> { service().preview(serverId) }
            error.message shouldContain "no enabled mods"
        }

        test("rejects when no mods are required on the client") {
            val serverId = createServer(createNode())
            addMod(serverId, "chunky", "Chunky")

            val error = shouldThrow<UnprocessableException> { service().preview(serverId) }
            error.message shouldContain "required on the client"
        }
    })
