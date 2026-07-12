package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.*
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

@Tags("ServerOps")
class ServerModsTest : BaseSystemTest() {

    init {

        lateinit var serverId: String

        beforeSpec {
            serverId = helper.createTestServer(nodeId)
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
                        pinnedVersionId = "mc1.21-0.13.0"
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
                        pinnedVersionId = "mc1.21-0.13.0"
                    )
                )
                api.addMod(
                    serverId,
                    CreateModRequest(
                        modrinthProjectId = "sodium",
                        displayName = "Sodium",
                        pinStrategy = ModPinStrategy.PINNED,
                        pinnedVersionId = "mc1.21-0.5.0"
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
                        pinnedVersionId = "mc1.21-0.13.0"
                    )
                )
                val ex = shouldThrow<ClientException> {
                    api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = ModPinStrategy.PINNED,
                            pinnedVersionId = "mc1.21-0.13.0"
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
                        pinnedVersionId = "mc1.21-0.13.0"
                    )
                )
                api.updateMod(
                    serverId,
                    mod.id,
                    PatchModRequest(pinnedVersionId = "mc1.21-0.14.0")
                )
                val mods = api.listMods(serverId)
                val updated = mods.values.flatten()
                    .first { it.id == mod.id }
                updated.pinnedVersionId shouldBe "mc1.21-0.14.0"
            }

            should("changes strategy from PINNED to LATEST") {
                val mod = api.addMod(
                    serverId,
                    CreateModRequest(
                        modrinthProjectId = "lithium",
                        displayName = "Lithium",
                        pinStrategy = ModPinStrategy.PINNED,
                        pinnedVersionId = "mc1.21-0.13.0"
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
                        pinnedVersionId = "mc1.21-0.13.0"
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
