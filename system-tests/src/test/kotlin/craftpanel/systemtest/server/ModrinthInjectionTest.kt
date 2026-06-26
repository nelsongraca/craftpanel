package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.CreateModRequest
import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.client.model.ModPinStrategy
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlin.random.Random

class ModrinthInjectionTest : BaseSystemTest() {

    init {
        lateinit var serverId: String

        beforeSpec {
            serverId = helper.createTestServer(nodeId)
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
                api.listMods(serverId).values.flatten().forEach { mod ->
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
                    api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = ModPinStrategy.PINNED,
                            pinnedVersionId = "mc1.21-0.13.0",
                        )
                    )

                    api.restartServer(serverId)
                    awaitRestartCycle(helper, serverId)

                    val info = docker.inspectContainerCmd(containerName(serverId))
                        .exec()
                    val env = info.config?.env?.toList()
                        .orEmpty()
                    env shouldContain "MODRINTH_PROJECTS=lithium:mc1.21-0.13.0"
                }

                should("adding a second mod includes both in MODRINTH_PROJECTS") {
                    api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = ModPinStrategy.PINNED,
                            pinnedVersionId = "mc1.21-0.13.0",
                        )
                    )
                    api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "sodium",
                            displayName = "Sodium",
                            pinStrategy = ModPinStrategy.PINNED,
                            pinnedVersionId = "mc1.21-0.5.0",
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
                    projects shouldContain "lithium:mc1.21-0.13.0"
                    projects shouldContain "sodium:mc1.21-0.5.0"
                }

                should("removing a mod and restarting removes it from MODRINTH_PROJECTS") {
                    api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = ModPinStrategy.PINNED,
                            pinnedVersionId = "mc1.21-0.13.0",
                        )
                    )
                    val sodium = api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "sodium",
                            displayName = "Sodium",
                            pinStrategy = ModPinStrategy.PINNED,
                            pinnedVersionId = "mc1.21-0.5.0",
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
                    projects shouldContain "lithium:mc1.21-0.13.0"
                    projects shouldNotContain "sodium:mc1.21-0.5.0"
                }
            }

            context("LATEST strategy") {

                should("a LATEST mod appears in MODRINTH_PROJECTS without a version pin") {
                    api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = ModPinStrategy.LATEST,
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
