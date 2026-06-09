package craftpanel.systemtest.server

import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException
import org.openapitools.client.infrastructure.ServerException

class ServerFilesTest : BaseSystemTest() {

    private lateinit var helper: ServerHelper
    private lateinit var serverId: String

    init {
        beforeSpec {
            helper = ServerHelper(api)
            serverId = helper.createTestServer(nodeId)
            api.startServer(serverId)
            helper.awaitStatus(serverId, "HEALTHY", 60_000)
        }

        afterSpec {
            runCatching { api.stopServer(serverId) }
            helper.awaitStoppedOrGone(serverId)
            runCatching { api.deleteServer(serverId) }
        }

        describe("Server file operations") {

            it("returns 404 for non-existent server") {
                val ex = shouldThrow<ClientException> {
                    api.listServerFiles("00000000-0000-0000-0000-000000000000")
                }
                ex.statusCode shouldBe 404
            }

            it("lists root directory") {
                val files = api.listServerFiles(serverId)
                files.path shouldBe "/"
            }

            it("lists subdirectory returns 502") {
                val ex = shouldThrow<ServerException> {
                    api.listServerFiles(serverId, path = "/nonexistent")
                }
                ex.statusCode shouldBe 502
            }

            it("lists on a stopped server") {
                api.stopServer(serverId)
                helper.awaitStoppedOrGone(serverId)
                val files = api.listServerFiles(serverId)
                files.path shouldBe "/"
                api.startServer(serverId)
                helper.awaitStatus(serverId, "HEALTHY", 60_000)
            }

            it("read file returns 500") {
                val ex = shouldThrow<ServerException> {
                    api.readServerFile(serverId, path = "/data/server.properties")
                }
                ex.statusCode shouldBe 500
            }

            it("mkdir returns 502") {
                val ex = shouldThrow<ServerException> {
                    api.mkdirServerFile(serverId, craftpanel.systemtest.client.model.MkdirRequest(path = "/data/test-dir"))
                }
                ex.statusCode shouldBe 502
            }

            it("delete returns 500") {
                val ex = shouldThrow<ServerException> {
                    api.deleteServerFile(serverId, path = "/data/to-delete", recursive = true)
                }
                ex.statusCode shouldBe 500
            }

            it("move returns 500") {
                val ex = shouldThrow<ServerException> {
                    api.moveServerFile(
                        serverId,
                        craftpanel.systemtest.client.model.MoveRequest(
                            sourcePath = "/data/src",
                            destinationPath = "/data/dst"
                        )
                    )
                }
                ex.statusCode shouldBe 500
            }

            it("copy returns 500") {
                val ex = shouldThrow<ServerException> {
                    api.copyServerFile(
                        serverId,
                        craftpanel.systemtest.client.model.CopyRequest(
                            sourcePath = "/data/src",
                            destinationPath = "/data/dst",
                            recursive = true
                        )
                    )
                }
                ex.statusCode shouldBe 500
            }
        }
    }
}
