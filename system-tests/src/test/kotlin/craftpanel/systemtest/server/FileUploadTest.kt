package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

@Isolate
@Tags("ServerCore", "ServerCoreRuntime")
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

            should("upload a text file and show it in the listing") {
                val content = "uploaded content ${System.currentTimeMillis()}"
                helper.uploadFile(serverId, "/uploaded.txt", content.toByteArray(), authHelper.token)
                val listing = api.listServerFiles(serverId)
                listing.propertyEntries.map { it.name } shouldContain "uploaded.txt"
            }

            should("read uploaded file content back") {
                val content = "hello from upload"
                helper.uploadFile(serverId, "/readable.txt", content.toByteArray(), authHelper.token)
                val result = api.readServerFile(serverId, path = "/readable.txt")
                result.content shouldBe content
                result.encoding shouldBe "utf-8"
            }

            should("overwrite an existing file on upload") {
                api.writeServerFile(serverId, path = "/overwrite.txt", body = "original")
                helper.uploadFile(serverId, "/overwrite.txt", "replaced".toByteArray(), authHelper.token)
                val result = api.readServerFile(serverId, path = "/overwrite.txt")
                result.content shouldBe "replaced"
            }

            should("return 404 for a non-existent server") {
                val result = runCatching {
                    helper.uploadFile("00000000-0000-0000-0000-000000000000", "/x.txt", "x".toByteArray(), authHelper.token)
                }
                result.exceptionOrNull()?.message shouldContain "404"
            }
        }

        context("uploadServerFiles") {

            should("upload multiple files in one batch and preserve nested paths") {
                val result = helper.uploadFiles(
                    serverId,
                    "/batch",
                    mapOf(
                        "a.txt" to "first".toByteArray(),
                        "nested/b.txt" to "second".toByteArray(),
                        "nested/deeper/c.txt" to "third".toByteArray()
                    ),
                    authHelper.token
                )

                result.uploaded shouldBe 3
                result.failed shouldBe 0
                result.files.all { it.success } shouldBe true
                api.readServerFile(serverId, "/batch/a.txt").content shouldBe "first"
                api.readServerFile(serverId, "/batch/nested/b.txt").content shouldBe "second"
                api.readServerFile(serverId, "/batch/nested/deeper/c.txt").content shouldBe "third"
            }

            should("overwrite existing files in a batch") {
                api.writeServerFile(serverId, path = "/batch-overwrite/a.txt", body = "old")
                val result = helper.uploadFiles(
                    serverId,
                    "/batch-overwrite",
                    mapOf("a.txt" to "new".toByteArray()),
                    authHelper.token
                )

                result.uploaded shouldBe 1
                api.readServerFile(serverId, "/batch-overwrite/a.txt").content shouldBe "new"
            }

            should("report a per-file failure without abandoning the batch") {
                // `blocker` is a regular file, so `blocker/child.txt` cannot be created.
                api.writeServerFile(serverId, path = "/batch-errors/blocker", body = "x")

                val result = helper.uploadFiles(
                    serverId,
                    "/batch-errors",
                    mapOf(
                        "blocker/child.txt" to "nope".toByteArray(),
                        "good.txt" to "ok".toByteArray()
                    ),
                    authHelper.token
                )

                result.uploaded shouldBe 1
                result.failed shouldBe 1
                result.files.first { it.path.endsWith("blocker/child.txt") }.success shouldBe false
                api.readServerFile(serverId, "/batch-errors/good.txt").content shouldBe "ok"
            }
        }
    }
}
