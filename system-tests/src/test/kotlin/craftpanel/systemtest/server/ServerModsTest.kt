package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.CreateModRequest
import craftpanel.systemtest.client.model.PatchModRequest
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

class ServerModsTest : BaseSystemTest() {

    init {
        describe("Server mods management") {

            describe("CRUD") {
                val helper = ServerHelper(api)
                lateinit var serverId: String

                beforeEach {
                    serverId = helper.createTestServer(nodeId)
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY", 60_000)
                }
                afterEach {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }

                it("lists mods as empty for a new server") {
                    val mods = api.listMods(serverId)
                    val entries = mods.getOrDefault("mods", emptyList())
                    entries.shouldBeEmpty()
                }

                it("adds a mod with LATEST pin strategy") {
                    val mod = api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = "LATEST"
                        )
                    )
                    mod.modrinthProjectId shouldBe "lithium"
                    mod.pinStrategy shouldBe "LATEST"
                    mod.pinnedVersionId shouldBe null
                }

                it("adds a mod with PINNED strategy and version") {
                    val mod = api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = "PINNED",
                            pinnedVersionId = "mc1.21-0.13.0"
                        )
                    )
                    mod.modrinthProjectId shouldBe "lithium"
                    mod.pinStrategy shouldBe "PINNED"
                    mod.pinnedVersionId shouldBe "mc1.21-0.13.0"
                }

                it("lists mods after adding one") {
                    api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = "LATEST"
                        )
                    )
                    val mods = api.listMods(serverId)
                    val entries = mods.getOrDefault("mods", emptyList())
                    entries.shouldHaveSize(1)
                    entries.first().modrinthProjectId shouldBe "lithium"
                }

                it("updates a mod's pin strategy and version") {
                    val added = api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = "LATEST"
                        )
                    )
                    val updated = api.updateMod(
                        serverId,
                        added.id,
                        PatchModRequest(
                            pinStrategy = "PINNED",
                            pinnedVersionId = "mc1.21-0.13.1"
                        )
                    )
                    updated.pinStrategy shouldBe "PINNED"
                    updated.pinnedVersionId shouldBe "mc1.21-0.13.1"
                }

                it("deletes a mod") {
                    val added = api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = "LATEST"
                        )
                    )
                    api.deleteMod(serverId, added.id)
                    val mods = api.listMods(serverId)
                    val entries = mods.getOrDefault("mods", emptyList())
                    entries.shouldBeEmpty()
                }

                it("deleting non-existent mod returns 404") {
                    val ex = shouldThrow<ClientException> {
                        api.deleteMod(serverId, "00000000-0000-0000-0000-000000000000")
                    }
                    ex.statusCode shouldBe 404
                }

                it("adding duplicate modrinth project returns 409") {
                    api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "fabric-api",
                            displayName = "Fabric API",
                            pinStrategy = "LATEST"
                        )
                    )
                    val ex = shouldThrow<ClientException> {
                        api.addMod(
                            serverId,
                            CreateModRequest(
                                modrinthProjectId = "fabric-api",
                                displayName = "Fabric API",
                                pinStrategy = "LATEST"
                            )
                        )
                    }
                    ex.statusCode shouldBe 409
                }
            }

            describe("errors") {
                it("returns 404 for non-existent server") {
                    val ex = shouldThrow<ClientException> {
                        api.listMods("00000000-0000-0000-0000-000000000000")
                    }
                    ex.statusCode shouldBe 404
                }
            }
        }
    }
}
