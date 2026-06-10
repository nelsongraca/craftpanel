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
        describe("Server mods CRUD") {

            describe("listMods") {
                val helper = ServerHelper(api)
                lateinit var serverId: String

                beforeEach {
                    serverId = helper.createTestServer(nodeId)
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                }
                afterEach {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }

                it("new server has no mods") {
                    val mods = api.listMods(serverId)
                    mods.isEmpty() shouldBe true
                }

                it("after adding one mod, list contains it") {
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

                it("after adding two mods, both are present") {
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
                    val projects = mods.values.flatten().map { it.modrinthProjectId }
                    projects shouldHaveSize 2
                    projects shouldContain "lithium"
                    projects shouldContain "sodium"
                }
            }

            describe("addMod") {
                val helper = ServerHelper(api)
                lateinit var serverId: String

                beforeEach {
                    serverId = helper.createTestServer(nodeId)
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                }
                afterEach {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }

                it("adding duplicate mod returns 409") {
                    api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = "PINNED",
                            pinnedVersionId = "mc1.21-0.13.0",
                        )
                    )
                    val ex = shouldThrow<ClientException> {
                        api.addMod(
                            serverId,
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

            describe("updateMod") {
                val helper = ServerHelper(api)
                lateinit var serverId: String

                beforeEach {
                    serverId = helper.createTestServer(nodeId)
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                }
                afterEach {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }

                it("changes version pin") {
                    val mod = api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = "PINNED",
                            pinnedVersionId = "mc1.21-0.13.0",
                        )
                    )
                    api.updateMod(
                        serverId, mod.id,
                        PatchModRequest(pinnedVersionId = "mc1.21-0.14.0")
                    )
                    val mods = api.listMods(serverId)
                    val updated = mods.values.flatten().first { it.id == mod.id }
                    updated.pinnedVersionId shouldBe "mc1.21-0.14.0"
                }

                it("changes strategy from PINNED to LATEST") {
                    val mod = api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = "PINNED",
                            pinnedVersionId = "mc1.21-0.13.0",
                        )
                    )
                    api.updateMod(
                        serverId, mod.id,
                        PatchModRequest(pinStrategy = "LATEST", pinnedVersionId = null)
                    )
                    val mods = api.listMods(serverId)
                    val updated = mods.values.flatten().first { it.id == mod.id }
                    updated.pinStrategy shouldBe "LATEST"
                    updated.pinnedVersionId shouldBe null
                }
            }

            describe("deleteMod") {
                val helper = ServerHelper(api)
                lateinit var serverId: String

                beforeEach {
                    serverId = helper.createTestServer(nodeId)
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                }
                afterEach {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }

                it("removes an existing mod") {
                    val mod = api.addMod(
                        serverId,
                        CreateModRequest(
                            modrinthProjectId = "lithium",
                            displayName = "Lithium",
                            pinStrategy = "PINNED",
                            pinnedVersionId = "mc1.21-0.13.0",
                        )
                    )
                    api.deleteMod(serverId, mod.id)
                    val mods = api.listMods(serverId)
                    mods.isEmpty() shouldBe true
                }

                it("returns 404 for non-existent mod") {
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
}
