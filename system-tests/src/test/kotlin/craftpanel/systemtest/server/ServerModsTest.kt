package craftpanel.systemtest.server

import com.google.gson.JsonParser
import craftpanel.systemtest.client.model.*
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient
import okhttp3.Request
import org.openapitools.client.infrastructure.ClientException
import kotlin.random.Random

@Tags("ServerOps")
class ServerModsTest : BaseSystemTest() {

    init {

        lateinit var serverId: String
        val httpClient = OkHttpClient()

        fun resolveModrinthVersionIds(projectId: String, loader: String, mcVersion: String, count: Int = 2): List<String> {
            val url = "https://api.modrinth.com/v2/project/$projectId/version" +
                "?loaders=%5B%22$loader%22%5D&game_versions=%5B%22$mcVersion%22%5D"
            val body = httpClient.newCall(Request.Builder().url(url).build())
                .execute()
                .use { it.body.string() }
            val versions = JsonParser.parseString(body).asJsonArray
            check(versions.size() >= count) {
                "Only ${versions.size()} Modrinth version(s) of '$projectId' compatible with $loader $mcVersion, need $count"
            }
            return (0 until count).map { i ->
                versions[i].asJsonObject["id"].asString
            }
        }

        lateinit var lithiumVersion: String
        lateinit var lithiumVersion2: String
        lateinit var sodiumVersion: String

        beforeSpec {
            lithiumVersion = resolveModrinthVersionIds("lithium", "fabric", "1.21.4", 2).first()
            lithiumVersion2 = resolveModrinthVersionIds("lithium", "fabric", "1.21.4", 2).last()
            sodiumVersion = resolveModrinthVersionIds("sodium", "fabric", "1.21.4").first()
            serverId = api.createServer(
                CreateServerRequest(
                    name = "test-mods-${System.currentTimeMillis()}-${Random.nextInt(100000)}",
                    nodeId = nodeId,
                    serverType = "FABRIC",
                    mcVersion = "1.21.4",
                    itzgImageTag = "latest",
                    memoryMb = 512,
                    cpuShares = 0
                )
            ).id
            api.startServer(serverId)
            helper.awaitStatus(serverId, ServerStatus.HEALTHY)
        }
        afterSpec {
            runCatching { api.stopServer(serverId) }
            helper.awaitStoppedOrGone(serverId)
            runCatching { api.deleteServer(serverId) }
        }
        beforeEach {
            api.listMods(serverId).values.flatten()
                .forEach { api.deleteMod(serverId, it.id) }
        }

        context("listMods") {

            should("new server has no mods") {
                val mods = api.listMods(serverId)
                mods.values.flatten()
                    .isEmpty() shouldBe true
            }

            should("after adding one mod, list contains it") {
                api.addMod(
                    serverId,
                    CreateModRequest(
                        modrinthProjectId = "lithium",
                        displayName = "Lithium",
                        pinStrategy = ModPinStrategy.PINNED,
                        pinnedVersionId = lithiumVersion
                    )
                )
                val mods = api.listMods(serverId)
                val all = mods.values.flatten()
                all.shouldHaveSize(1)
                all.first().modrinthProjectId shouldBe "lithium"
            }

            should("after adding two mods, both are present") {
                api.addMod(
                    serverId,
                    CreateModRequest(
                        modrinthProjectId = "lithium",
                        displayName = "Lithium",
                        pinStrategy = ModPinStrategy.PINNED,
                        pinnedVersionId = lithiumVersion
                    )
                )
                api.addMod(
                    serverId,
                    CreateModRequest(
                        modrinthProjectId = "sodium",
                        displayName = "Sodium",
                        pinStrategy = ModPinStrategy.PINNED,
                        pinnedVersionId = sodiumVersion
                    )
                )
                val mods = api.listMods(serverId)
                val projects = mods.values.flatten()
                    .map { it.modrinthProjectId }
                projects shouldHaveSize 2
                projects shouldContain "lithium"
                projects shouldContain "sodium"
            }
        }

        context("addMod") {

            should("adding duplicate mod returns 409") {
                api.addMod(
                    serverId,
                    CreateModRequest(
                        modrinthProjectId = "lithium",
                        displayName = "Lithium",
                        pinStrategy = ModPinStrategy.PINNED,
                        pinnedVersionId = lithiumVersion
                    )
                )
                val ex = shouldThrow<ClientException> {
                    api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = ModPinStrategy.PINNED,
                            pinnedVersionId = lithiumVersion
                        )
                    )
                }
                ex.statusCode shouldBe 409
            }
        }

        context("updateMod") {

            should("changes version pin") {
                val mod = api.addMod(
                    serverId,
                    CreateModRequest(
                        modrinthProjectId = "lithium",
                        displayName = "Lithium",
                        pinStrategy = ModPinStrategy.PINNED,
                        pinnedVersionId = lithiumVersion
                    )
                )
                api.updateMod(
                    serverId,
                    mod.id,
                    PatchModRequest(pinnedVersionId = lithiumVersion2)
                )
                val mods = api.listMods(serverId)
                val updated = mods.values.flatten()
                    .first { it.id == mod.id }
                updated.pinnedVersionId shouldBe lithiumVersion2
            }

            should("changes strategy from PINNED to LATEST") {
                val mod = api.addMod(
                    serverId,
                    CreateModRequest(
                        modrinthProjectId = "lithium",
                        displayName = "Lithium",
                        pinStrategy = ModPinStrategy.PINNED,
                        pinnedVersionId = lithiumVersion
                    )
                )
                api.updateMod(
                    serverId,
                    mod.id,
                    PatchModRequest(pinStrategy = ModPinStrategy.LATEST, pinnedVersionId = null)
                )
                val mods = api.listMods(serverId)
                val updated = mods.values.flatten()
                    .first { it.id == mod.id }
                updated.pinStrategy shouldBe ModPinStrategy.LATEST
                updated.pinnedVersionId shouldBe null
            }
        }

        context("deleteMod") {

            should("removes an existing mod") {
                val mod = api.addMod(
                    serverId,
                    CreateModRequest(
                        modrinthProjectId = "lithium",
                        displayName = "Lithium",
                        pinStrategy = ModPinStrategy.PINNED,
                        pinnedVersionId = lithiumVersion
                    )
                )
                api.deleteMod(serverId, mod.id)
                val mods = api.listMods(serverId)
                mods.values.flatten()
                    .isEmpty() shouldBe true
            }

            should("returns 404 for non-existent mod") {
                val ex = shouldThrow<ClientException> {
                    api.deleteMod(
                        serverId,
                        "00000000-0000-0000-0000-000000000000"
                    )
                }
                ex.statusCode shouldBe 404
            }
        }
    }
}
