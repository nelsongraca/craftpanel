package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.CreateModRequest
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlin.random.Random

class ModrinthInjectionTest : BaseSystemTest() {

    init {
        context("Modrinth mod injection") {

            context("PINNED strategy") {

                should("MODRINTH_PROJECTS env var is absent on a server with no mods") {

                val serverId = helper.createTestServer(nodeId)
                    try {
                        api.startServer(serverId)
                        helper.awaitStatus(serverId, "HEALTHY")

                        val info = docker.inspectContainerCmd(containerName(serverId))
                            .exec()
                        val envKeys = info.config?.env?.map { it.substringBefore("=") }
                            .orEmpty()
                        envKeys shouldNotContain "MODRINTH_PROJECTS"
                    }
                    finally {
                        runCatching { api.stopServer(serverId) }
                        helper.awaitStoppedOrGone(serverId)
                        runCatching { api.deleteServer(serverId) }
                    }
                }

                should("adding a pinned mod and restarting injects MODRINTH_PROJECTS into the container") {

                val serverId = helper.createTestServer(nodeId)
                    try {
                        api.startServer(serverId)
                        helper.awaitStatus(serverId, "HEALTHY")

                        api.addMod(
                            serverId,
                            CreateModRequest(
                                modrinthProjectId = "lithium",
                                displayName = "Lithium",
                                pinStrategy = "PINNED",
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
                    finally {
                        runCatching { api.stopServer(serverId) }
                        helper.awaitStoppedOrGone(serverId)
                        runCatching { api.deleteServer(serverId) }
                    }
                }

                should("adding a second mod includes both in MODRINTH_PROJECTS") {

                val serverId = helper.createTestServer(nodeId)
                    try {
                        api.startServer(serverId)
                        helper.awaitStatus(serverId, "HEALTHY")

                        api.addMod(
                            serverId,
                            CreateModRequest(
                                modrinthProjectId = "lithium",
                                displayName = "Lithium",
                                pinStrategy = "PINNED",
                                pinnedVersionId = "mc1.21-0.13.0",
                            )
                        )
                        api.addMod(
                            serverId,
                            CreateModRequest(
                                modrinthProjectId = "sodium",
                                displayName = "Sodium",
                                pinStrategy = "PINNED",
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
                    finally {
                        runCatching { api.stopServer(serverId) }
                        helper.awaitStoppedOrGone(serverId)
                        runCatching { api.deleteServer(serverId) }
                    }
                }

                should("removing a mod and restarting removes it from MODRINTH_PROJECTS") {

                val serverId = helper.createTestServer(nodeId)
                    try {
                        api.startServer(serverId)
                        helper.awaitStatus(serverId, "HEALTHY")

                        api.addMod(
                            serverId,
                            CreateModRequest(
                                modrinthProjectId = "lithium",
                                displayName = "Lithium",
                                pinStrategy = "PINNED",
                                pinnedVersionId = "mc1.21-0.13.0",
                            )
                        )
                        val sodium = api.addMod(
                            serverId,
                            CreateModRequest(
                                modrinthProjectId = "sodium",
                                displayName = "Sodium",
                                pinStrategy = "PINNED",
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
                    finally {
                        runCatching { api.stopServer(serverId) }
                        helper.awaitStoppedOrGone(serverId)
                        runCatching { api.deleteServer(serverId) }
                    }
                }
            }

            context("LATEST strategy") {

                should("a LATEST mod appears in MODRINTH_PROJECTS without a version pin") {

                val serverId = helper.createTestServer(nodeId)
                    try {
                        api.startServer(serverId)
                        helper.awaitStatus(serverId, "HEALTHY")

                        api.addMod(
                            serverId,
                            CreateModRequest(
                                modrinthProjectId = "lithium",
                                displayName = "Lithium",
                                pinStrategy = "LATEST",
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
                    finally {
                        runCatching { api.stopServer(serverId) }
                        helper.awaitStoppedOrGone(serverId)
                        runCatching { api.deleteServer(serverId) }
                    }
                }
            }

        }
    }

    private suspend fun awaitRestartCycle(helper: ServerHelper, serverId: String, timeoutMs: Long = 120_000) {
        val phaseTimeoutMs = timeoutMs / 2

        // Phase 1: wait for server to leave HEALTHY (restart in progress)
        val phase1Deadline = System.currentTimeMillis() + phaseTimeoutMs
        var interval = 100L
        while (System.currentTimeMillis() < phase1Deadline) {
            val status = runCatching { api.getServer(serverId).status }.getOrNull()
            if (status != "HEALTHY") break
            val jitter = Random.nextLong(-(interval / 5), interval / 5 + 1)
            delay((interval + jitter).coerceAtLeast(50))
            interval = (interval * 1.5).toLong()
                .coerceAtMost(1000)
        }

        // Phase 2: wait for server to return to HEALTHY
        helper.awaitStatus(serverId, "HEALTHY", phaseTimeoutMs)
    }
}
