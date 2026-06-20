package craftpanel.systemtest.server

import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

class FileUploadTest : BaseSystemTest() {

    init {

        lateinit var serverId: String

        beforeSpec {
            serverId = helper.createTestServer(nodeId)
            api.startServer(serverId)
            helper.awaitStatus(serverId, "HEALTHY")
        }

        afterSpec {
            runCatching { api.stopServer(serverId) }
            helper.awaitStoppedOrGone(serverId)
            runCatching { api.deleteServer(serverId) }
        }

        context("uploadServerFile") {

            should("uploads a text file and it appears in the listing") {
                val content = "uploaded content ${System.currentTimeMillis()}"
                api.uploadServerFile(
                    serverId,
                    path = "/uploaded.txt",
                    file = content.toByteArray()
                        .map { it.toInt() and 0xFF },
                )
                val listing = api.listServerFiles(serverId)
                listing.propertyEntries.map { it.name } shouldContain "uploaded.txt"
            }

            should("uploaded file content is readable back") {
                val content = "hello from upload"
                api.uploadServerFile(
                    serverId,
                    path = "/readable.txt",
                    file = content.toByteArray()
                        .map { it.toInt() and 0xFF },
                )
                val result = api.readServerFile(serverId, path = "/readable.txt")
                result.content shouldBe content
                result.encoding shouldBe "utf-8"
            }

            should("upload overwrites an existing file") {
                api.writeServerFile(serverId, path = "/overwrite.txt", body = "original")
                api.uploadServerFile(
                    serverId,
                    path = "/overwrite.txt",
                    file = "replaced".toByteArray()
                        .map { it.toInt() and 0xFF },
                )
                val result = api.readServerFile(serverId, path = "/overwrite.txt")
                result.content shouldBe "replaced"
            }

            should("returns 404 for non-existent server") {
                val ex = shouldThrow<ClientException> {
                    api.uploadServerFile(
                        "00000000-0000-0000-0000-000000000000",
                        path = "/x.txt",
                        file = "x".toByteArray()
                            .map { it.toInt() and 0xFF },
                    )
                }
                ex.statusCode shouldBe 404
            }
        }
    }
}
