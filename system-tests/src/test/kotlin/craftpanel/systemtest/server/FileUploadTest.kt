package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

@Isolate
@Tags("ServerCore")
class FileUploadTest : BaseSystemTest() {

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

        context("uploadServerFile") {

            should("uploads a text file and it appears in the listing") {
                val content = "uploaded content ${System.currentTimeMillis()}"
                helper.uploadFile(serverId, "/uploaded.txt", content.toByteArray(), authHelper.token)
                val listing = api.listServerFiles(serverId)
                listing.propertyEntries.map { it.name } shouldContain "uploaded.txt"
            }

            should("uploaded file content is readable back") {
                val content = "hello from upload"
                helper.uploadFile(serverId, "/readable.txt", content.toByteArray(), authHelper.token)
                val result = api.readServerFile(serverId, path = "/readable.txt")
                result.content shouldBe content
                result.encoding shouldBe "utf-8"
            }

            should("upload overwrites an existing file") {
                api.writeServerFile(serverId, path = "/overwrite.txt", body = "original")
                helper.uploadFile(serverId, "/overwrite.txt", "replaced".toByteArray(), authHelper.token)
                val result = api.readServerFile(serverId, path = "/overwrite.txt")
                result.content shouldBe "replaced"
            }

            should("returns 404 for non-existent server") {
                val result = runCatching {
                    helper.uploadFile("00000000-0000-0000-0000-000000000000", "/x.txt", "x".toByteArray(), authHelper.token)
                }
                result.exceptionOrNull()?.message shouldContain "404"
            }
        }
    }
}
