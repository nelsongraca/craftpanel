package craftpanel.systemtest.server

import com.google.gson.JsonParser
import craftpanel.systemtest.client.model.*
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.openapitools.client.infrastructure.ClientException
import kotlin.random.Random

@Tags("ServerOps")
class ModrinthInjectionTest : BaseSystemTest() {

    init {
        lateinit var serverId: String
        val httpClient = OkHttpClient()

        // Resolves a live Modrinth version id for [projectId] compatible with [mcVersion] on the given [loader], so
        // fixtures never rot against Modrinth's actual catalog the way hand-picked version numbers eventually did.
        fun resolveModrinthVersionId(projectId: String, loader: String, mcVersion: String): String {
            val url = "https://api.modrinth.com/v2/project/$projectId/version" +
                "?loaders=%5B%22$loader%22%5D&game_versions=%5B%22$mcVersion%22%5D"
            val body = httpClient.newCall(Request.Builder().url(url).build())
                .execute()
                .use { it.body.string() }
            val versions = JsonParser.parseString(body).asJsonArray
            check(versions.size() > 0) { "No Modrinth version of '$projectId' compatible with $loader $mcVersion - fixture needs updating" }
            return versions[0].asJsonObject["id"].asString
        }

        beforeSpec {
            // FABRIC, not the shared PAPER helper server: lithium/sodium/fabric-api are Fabric-loader mods, and
            // addMod now validates loader+mcVersion compatibility server-side.
            serverId = api.createServer(
                CreateServerRequest(
                    name = "test-modrinth-${System.currentTimeMillis()}-${Random.nextInt(100000)}",
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
            helper.awaitContainerLog(containerName(serverId), "stdin listener ready", docker)
        }

        afterSpec {
            runCatching { api.stopServer(serverId) }
            helper.awaitStoppedOrGone(serverId)
            runCatching { api.deleteServer(serverId) }
        }

        afterTest {
            // Clear all mods so each test starts from a clean mod state
            runCatching {
                api.listMods(serverId).values.flatten()
                    .forEach { mod ->
                        runCatching { api.deleteMod(serverId, mod.id) }
                    }
            }
        }

        context("Modrinth mod injection") {

            context("PINNED strategy") {

                should("MODRINTH_PROJECTS env var is absent on a server with no mods") {
                    val info = docker.inspectContainerCmd(containerName(serverId))
                        .exec()
                    val envKeys = info.config?.env?.map { it.substringBefore("=") }
                        .orEmpty()
                    envKeys shouldNotContain "MODRINTH_PROJECTS"
                }

                should("adding a pinned mod and restarting injects MODRINTH_PROJECTS into the container") {
                    val lithiumVersion = resolveModrinthVersionId("lithium", "fabric", "1.21.4")
                    api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = ModPinStrategy.PINNED,
                            pinnedVersionId = lithiumVersion
                        )
                    )

                    api.restartServer(serverId)
                    awaitRestartCycle(helper, serverId)

                    val info = docker.inspectContainerCmd(containerName(serverId))
                        .exec()
                    val env = info.config?.env?.toList()
                        .orEmpty()
                    env shouldContain "MODRINTH_PROJECTS=lithium:$lithiumVersion"
                }

                should("adding a second mod includes both in MODRINTH_PROJECTS") {
                    val lithiumVersion = resolveModrinthVersionId("lithium", "fabric", "1.21.4")
                    val sodiumVersion = resolveModrinthVersionId("sodium", "fabric", "1.21.4")
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

                    api.restartServer(serverId)
                    awaitRestartCycle(helper, serverId)

                    val info = docker.inspectContainerCmd(containerName(serverId))
                        .exec()
                    val env = info.config?.env?.toList()
                        .orEmpty()
                    val modrinthEntry = env.firstOrNull { it.startsWith("MODRINTH_PROJECTS=") }
                    val projects = modrinthEntry?.removePrefix("MODRINTH_PROJECTS=")
                        ?.split(",")
                        .orEmpty()
                    projects shouldContain "lithium:$lithiumVersion"
                    projects shouldContain "sodium:$sodiumVersion"
                }

                should("removing a mod and restarting removes it from MODRINTH_PROJECTS") {
                    val lithiumVersion = resolveModrinthVersionId("lithium", "fabric", "1.21.4")
                    val sodiumVersion = resolveModrinthVersionId("sodium", "fabric", "1.21.4")
                    api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = ModPinStrategy.PINNED,
                            pinnedVersionId = lithiumVersion
                        )
                    )
                    val sodium = api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "sodium",
                            displayName = "Sodium",
                            pinStrategy = ModPinStrategy.PINNED,
                            pinnedVersionId = sodiumVersion
                        )
                    )

                    api.restartServer(serverId)
                    awaitRestartCycle(helper, serverId)

                    api.deleteMod(serverId, sodium.id)

                    api.restartServer(serverId)
                    awaitRestartCycle(helper, serverId)

                    val info = docker.inspectContainerCmd(containerName(serverId))
                        .exec()
                    val env = info.config?.env?.toList()
                        .orEmpty()
                    val modrinthEntry = env.firstOrNull { it.startsWith("MODRINTH_PROJECTS=") }
                    val projects = modrinthEntry?.removePrefix("MODRINTH_PROJECTS=")
                        ?.split(",")
                        .orEmpty()
                    projects shouldContain "lithium:$lithiumVersion"
                    projects shouldNotContain "sodium:$sodiumVersion"
                }
            }

            context("LATEST strategy") {

                should("a LATEST mod appears in MODRINTH_PROJECTS without a version pin") {
                    api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = ModPinStrategy.LATEST
                        )
                    )

                    api.restartServer(serverId)
                    awaitRestartCycle(helper, serverId)

                    val info = docker.inspectContainerCmd(containerName(serverId))
                        .exec()
                    val env = info.config?.env?.toList()
                        .orEmpty()
                    val modrinthEntry = env.firstOrNull { it.startsWith("MODRINTH_PROJECTS=") }
                    val projects = modrinthEntry?.removePrefix("MODRINTH_PROJECTS=")
                        ?.split(",")
                        .orEmpty()
                    // LATEST entries use just the project ID with no version suffix
                    projects shouldContain "lithium"
                    projects.none { it.startsWith("lithium:") } shouldBe true
                }

                should("adding a mod with no version compatible with the server's loader+mcVersion is rejected") {
                    // essentialsx is a Paper/Spigot-only plugin, never published a Fabric build - exercises the
                    // root-cause fix: addMod validates loader+mcVersion compatibility before persisting, so an
                    // incompatible mod can never leave a server unable to boot once itzg tries to resolve it.
                    val ex = shouldThrow<ClientException> {
                        api.addMod(
                            serverId,
                            CreateModRequest(
                                modrinthProjectId = "essentialsx",
                                displayName = "EssentialsX",
                                pinStrategy = ModPinStrategy.LATEST
                            )
                        )
                    }
                    ex.statusCode shouldBe 422
                }
            }
        }
    }

    private suspend fun awaitRestartCycle(helper: ServerHelper, serverId: String, timeoutMs: Long = 120_000) {
        // Phase 1 cap: master writes STARTING synchronously before dispatching, so NOT-HEALTHY
        // should appear within seconds. Cap phase 1 tightly so load delays don't eat phase 2 budget.
        val phase1TimeoutMs = minOf(30_000L, timeoutMs / 4)
        val phase2TimeoutMs = timeoutMs - phase1TimeoutMs

        // Phase 1: wait for server to leave HEALTHY (restart in progress)
        val phase1Deadline = System.currentTimeMillis() + phase1TimeoutMs
        var interval = 100L
        while (System.currentTimeMillis() < phase1Deadline) {
            val status = runCatching { api.getServer(serverId).status }.getOrNull()
            if (status != ServerStatus.HEALTHY) break
            val jitter = Random.nextLong(-(interval / 5), interval / 5 + 1)
            delay((interval + jitter).coerceAtLeast(50))
            interval = (interval * 1.5).toLong()
                .coerceAtMost(1000)
        }

        // Phase 2: wait for server to return to HEALTHY
        helper.awaitStatus(serverId, ServerStatus.HEALTHY, phase2TimeoutMs)
    }
}
