package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.IocraftpanelmasterserviceCreateModRequest
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay

class ModrinthInjectionTest : BaseSystemTest() {

    init {
        describe("Modrinth mod injection") {

            describe("PINNED strategy") {

                it("MODRINTH_PROJECTS env var is absent on a server with no mods") {
                    val helper = ServerHelper(api)
                    val serverId = helper.createTestServer(nodeId)
                    try {
                        api.startServer(serverId)
                        helper.awaitStatus(serverId, "HEALTHY", 60_000)

                        val info = docker.inspectContainerCmd("craftpanel-$serverId").exec()
                        val envKeys = info.config?.env?.map { it.substringBefore("=") }.orEmpty()
                        envKeys shouldNotContain "MODRINTH_PROJECTS"
                    } finally {
                        runCatching { api.stopServer(serverId) }
                        helper.awaitStoppedOrGone(serverId)
                        runCatching { api.deleteServer(serverId) }
                    }
                }

                it("adding a pinned mod and restarting injects MODRINTH_PROJECTS into the container") {
                    val helper = ServerHelper(api)
                    val serverId = helper.createTestServer(nodeId)
                    try {
                        api.startServer(serverId)
                        helper.awaitStatus(serverId, "HEALTHY", 60_000)

                        api.addMod(
                            serverId,
                            IocraftpanelmasterserviceCreateModRequest(
                                modrinthProjectId = "lithium",
                                displayName = "Lithium",
                                pinStrategy = "PINNED",
                                pinnedVersionId = "mc1.21-0.13.0",
                            )
                        )

                        api.restartServer(serverId)
                        awaitRestartCycle(helper, serverId)

                        val info = docker.inspectContainerCmd("craftpanel-$serverId").exec()
                        val env = info.config?.env?.toList().orEmpty()
                        env shouldContain "MODRINTH_PROJECTS=lithium:mc1.21-0.13.0"
                    } finally {
                        runCatching { api.stopServer(serverId) }
                        helper.awaitStoppedOrGone(serverId)
                        runCatching { api.deleteServer(serverId) }
                    }
                }

                it("adding a second mod includes both in MODRINTH_PROJECTS") {
                    val helper = ServerHelper(api)
                    val serverId = helper.createTestServer(nodeId)
                    try {
                        api.startServer(serverId)
                        helper.awaitStatus(serverId, "HEALTHY", 60_000)

                        api.addMod(
                            serverId,
                            IocraftpanelmasterserviceCreateModRequest(
                                modrinthProjectId = "lithium",
                                displayName = "Lithium",
                                pinStrategy = "PINNED",
                                pinnedVersionId = "mc1.21-0.13.0",
                            )
                        )
                        api.addMod(
                            serverId,
                            IocraftpanelmasterserviceCreateModRequest(
                                modrinthProjectId = "sodium",
                                displayName = "Sodium",
                                pinStrategy = "PINNED",
                                pinnedVersionId = "mc1.21-0.5.0",
                            )
                        )

                        api.restartServer(serverId)
                        awaitRestartCycle(helper, serverId)

                        val info = docker.inspectContainerCmd("craftpanel-$serverId").exec()
                        val env = info.config?.env?.toList().orEmpty()
                        val modrinthEntry = env.firstOrNull { it.startsWith("MODRINTH_PROJECTS=") }
                        val projects = modrinthEntry?.removePrefix("MODRINTH_PROJECTS=")?.split(",").orEmpty()
                        projects shouldContain "lithium:mc1.21-0.13.0"
                        projects shouldContain "sodium:mc1.21-0.5.0"
                    } finally {
                        runCatching { api.stopServer(serverId) }
                        helper.awaitStoppedOrGone(serverId)
                        runCatching { api.deleteServer(serverId) }
                    }
                }

                it("removing a mod and restarting removes it from MODRINTH_PROJECTS") {
                    val helper = ServerHelper(api)
                    val serverId = helper.createTestServer(nodeId)
                    try {
                        api.startServer(serverId)
                        helper.awaitStatus(serverId, "HEALTHY", 60_000)

                        api.addMod(
                            serverId,
                            IocraftpanelmasterserviceCreateModRequest(
                                modrinthProjectId = "lithium",
                                displayName = "Lithium",
                                pinStrategy = "PINNED",
                                pinnedVersionId = "mc1.21-0.13.0",
                            )
                        )
                        val sodium = api.addMod(
                            serverId,
                            IocraftpanelmasterserviceCreateModRequest(
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

                        val info = docker.inspectContainerCmd("craftpanel-$serverId").exec()
                        val env = info.config?.env?.toList().orEmpty()
                        val modrinthEntry = env.firstOrNull { it.startsWith("MODRINTH_PROJECTS=") }
                        val projects = modrinthEntry?.removePrefix("MODRINTH_PROJECTS=")?.split(",").orEmpty()
                        projects shouldContain "lithium:mc1.21-0.13.0"
                        projects shouldNotContain "sodium:mc1.21-0.5.0"
                    } finally {
                        runCatching { api.stopServer(serverId) }
                        helper.awaitStoppedOrGone(serverId)
                        runCatching { api.deleteServer(serverId) }
                    }
                }
            }

            describe("LATEST strategy") {

                it("a LATEST mod appears in MODRINTH_PROJECTS without a version pin") {
                    val helper = ServerHelper(api)
                    val serverId = helper.createTestServer(nodeId)
                    try {
                        api.startServer(serverId)
                        helper.awaitStatus(serverId, "HEALTHY", 60_000)

                        api.addMod(
                            serverId,
                            IocraftpanelmasterserviceCreateModRequest(
                                modrinthProjectId = "lithium",
                                displayName = "Lithium",
                                pinStrategy = "LATEST",
                            )
                        )

                        api.restartServer(serverId)
                        awaitRestartCycle(helper, serverId)

                        val info = docker.inspectContainerCmd("craftpanel-$serverId").exec()
                        val env = info.config?.env?.toList().orEmpty()
                        val modrinthEntry = env.firstOrNull { it.startsWith("MODRINTH_PROJECTS=") }
                        val projects = modrinthEntry?.removePrefix("MODRINTH_PROJECTS=")?.split(",").orEmpty()
                        // LATEST entries use just the project ID with no version suffix
                        projects shouldContain "lithium"
                        projects.none { it.startsWith("lithium:") } shouldBe true
                    } finally {
                        runCatching { api.stopServer(serverId) }
                        helper.awaitStoppedOrGone(serverId)
                        runCatching { api.deleteServer(serverId) }
                    }
                }
            }

        }
    }

    private suspend fun awaitRestartCycle(helper: ServerHelper, serverId: String, timeoutMs: Long = 90_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        // Wait for server to leave HEALTHY (restart in progress)
        while (System.currentTimeMillis() < deadline) {
            val status = runCatching { api.getServer(serverId).status }.getOrNull()
            if (status != "HEALTHY") break
            delay(500)
        }
        // Wait for server to return to HEALTHY
        val remaining = deadline - System.currentTimeMillis()
        helper.awaitStatus(serverId, "HEALTHY", remaining.coerceAtLeast(1_000))
    }
}
