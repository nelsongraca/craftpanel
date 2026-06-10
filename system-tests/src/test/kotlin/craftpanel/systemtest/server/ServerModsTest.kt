package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.CreateModRequest
import craftpanel.systemtest.client.model.PatchModRequest
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

class ServerModsTest : BaseSystemTest() {

    init {
        val helper = ServerHelper(api)
        lateinit var serverId: String
        lateinit var serverId2: String
        lateinit var serverId3: String
        lateinit var serverId4: String

        beforeSpec {
            serverId = helper.createTestServer(nodeId)
            api.startServer(serverId)
            helper.awaitStatus(serverId, "HEALTHY")
            serverId2 = helper.createTestServer(nodeId)
            api.startServer(serverId2)
            helper.awaitStatus(serverId2, "HEALTHY")
            serverId3 = helper.createTestServer(nodeId)
            api.startServer(serverId3)
            helper.awaitStatus(serverId3, "HEALTHY")
            serverId4 = helper.createTestServer(nodeId)
            api.startServer(serverId4)
            helper.awaitStatus(serverId4, "HEALTHY")
        }
        afterSpec {
            runCatching { api.stopServer(serverId) }
            helper.awaitStoppedOrGone(serverId)
            runCatching { api.deleteServer(serverId) }
            runCatching { api.stopServer(serverId2) }
            helper.awaitStoppedOrGone(serverId2)
            runCatching { api.deleteServer(serverId2) }
            runCatching { api.stopServer(serverId3) }
            helper.awaitStoppedOrGone(serverId3)
            runCatching { api.deleteServer(serverId3) }
            runCatching { api.stopServer(serverId4) }
            helper.awaitStoppedOrGone(serverId4)
            runCatching { api.deleteServer(serverId4) }
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
                        pinStrategy = "PINNED",
                        pinnedVersionId = "mc1.21-0.13.0",
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
                    serverId2,
                    CreateModRequest(
                        modrinthProjectId = "lithium",
                        displayName = "Lithium",
                        pinStrategy = "PINNED",
                        pinnedVersionId = "mc1.21-0.13.0",
                    )
                )
                val ex = shouldThrow<ClientException> {
                    api.addMod(
                        serverId2,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = "PINNED",
                            pinnedVersionId = "mc1.21-0.13.0",
                        )
                    )
                }
                ex.statusCode shouldBe 409
            }
        }

        context("updateMod") {

            should("changes version pin") {
                val mod = api.addMod(
                    serverId3,
                    CreateModRequest(
                        modrinthProjectId = "lithium",
                        displayName = "Lithium",
                        pinStrategy = "PINNED",
                        pinnedVersionId = "mc1.21-0.13.0",
                    )
                )
                api.updateMod(
                    serverId3, mod.id,
                    PatchModRequest(pinnedVersionId = "mc1.21-0.14.0")
                )
                val mods = api.listMods(serverId3)
                val updated = mods.values.flatten()
                    .first { it.id == mod.id }
                updated.pinnedVersionId shouldBe "mc1.21-0.14.0"
            }

            should("changes strategy from PINNED to LATEST") {
                val mod = api.addMod(
                    serverId3,
                    CreateModRequest(
                        modrinthProjectId = "lithium",
                        displayName = "Lithium",
                        pinStrategy = "PINNED",
                        pinnedVersionId = "mc1.21-0.13.0",
                    )
                )
                api.updateMod(
                    serverId3, mod.id,
                    PatchModRequest(pinStrategy = "LATEST", pinnedVersionId = null)
                )
                val mods = api.listMods(serverId3)
                val updated = mods.values.flatten()
                    .first { it.id == mod.id }
                updated.pinStrategy shouldBe "LATEST"
                updated.pinnedVersionId shouldBe null
            }
        }

        context("deleteMod") {

            should("removes an existing mod") {
                val mod = api.addMod(
                    serverId4,
                    CreateModRequest(
                        modrinthProjectId = "lithium",
                        displayName = "Lithium",
                        pinStrategy = "PINNED",
                        pinnedVersionId = "mc1.21-0.13.0",
                    )
                )
                api.deleteMod(serverId4, mod.id)
                val mods = api.listMods(serverId4)
                mods.values.flatten()
                    .isEmpty() shouldBe true
            }

            should("returns 404 for non-existent mod") {
                val ex = shouldThrow<ClientException> {
                    api.deleteMod(
                        serverId4,
                        "00000000-0000-0000-0000-000000000000"
                    )
                }
                ex.statusCode shouldBe 404
            }
        }
    }
}