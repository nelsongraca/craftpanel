package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.CopyRequest
import craftpanel.systemtest.client.model.MkdirRequest
import craftpanel.systemtest.client.model.MoveRequest
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.openapitools.client.infrastructure.ClientException
import org.openapitools.client.infrastructure.ServerException
import java.nio.charset.StandardCharsets

class ServerFilesTest : BaseSystemTest() {

    init {
        describe("Server file operations") {

            describe("CRUD") {
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

                it("returns 404 for non-existent server") {
                    val ex = shouldThrow<ClientException> {
                        api.listServerFiles("00000000-0000-0000-0000-000000000000")
                    }
                    ex.statusCode shouldBe 404
                }

                it("lists root directory") {
                    val files = api.listServerFiles(serverId)
                    files.propertyEntries.find { it.name == "server.propertiee" }.
                    files.path shouldBe "/"
                }

                it("lists on a stopped server") {
                    api.stopServer(serverId)
                    helper.awaitStoppedOrGone(serverId)
                    val files = api.listServerFiles(serverId)
                    files.path shouldBe "/"
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                }

                it("creates a directory and appears in listing") {
                    api.mkdirServerFile(serverId, MkdirRequest(path = "/test-dir"))
                    val files = api.listServerFiles(serverId)
                    val entries = files.propertyEntries
                    entries.map { it.name } shouldContain "test-dir"
                    entries.first { it.name == "test-dir" }.isDirectory shouldBe true
                }

                it("creates nested directories") {
                    api.mkdirServerFile(serverId, MkdirRequest(path = "/a/b/c"))
                    val rootFiles = api.listServerFiles(serverId)
                    rootFiles.propertyEntries.map { it.name } shouldContain "a"
                    val subFiles = api.listServerFiles(serverId, path = "/a/b")
                    subFiles.propertyEntries.map { it.name } shouldContain "c"
                }

                it("writes a file and reads it back") {
                    api.writeServerFile(serverId, path = "/hello.txt", body = "Hello, World!")
                    val result = api.readServerFile(serverId, path = "/hello.txt")
                    result.content shouldBe "Hello, World!"
                    result.encoding shouldBe "utf-8"
                }

                it("overwrites an existing file") {
                    api.writeServerFile(serverId, path = "/data.txt", body = "original")
                    api.writeServerFile(serverId, path = "/data.txt", body = "replaced")
                    val result = api.readServerFile(serverId, path = "/data.txt")
                    result.content shouldBe "replaced"
                }

                it("writes empty file content") {
                    api.writeServerFile(serverId, path = "/empty.txt", body = "")
                    val result = api.readServerFile(serverId, path = "/empty.txt")
                    result.content shouldBe ""
                }

                it("deletes a file") {
                    api.writeServerFile(serverId, path = "/delete-me.txt", body = "bye")
                    api.deleteServerFile(serverId, path = "/delete-me.txt")
                    val ex = shouldThrow<Exception> {
                        api.readServerFile(serverId, path = "/delete-me.txt")
                    }
                    (ex as? ClientException)?.statusCode shouldBe 404
                }

                it("deleting non-existent file returns 404") {
                    val ex = shouldThrow<ClientException> {
                        api.deleteServerFile(serverId, path = "/does-not-exist", recursive = false)
                    }
                    ex.statusCode shouldBe 404
                }

                it("deleting non-empty directory without recursive returns 409") {
                    api.mkdirServerFile(serverId, MkdirRequest(path = "/my-dir"))
                    api.writeServerFile(serverId, path = "/my-dir/file.txt", body = "inside")
                    val ex = shouldThrow<ClientException> {
                        api.deleteServerFile(serverId, path = "/my-dir", recursive = false)
                    }
                    ex.statusCode shouldBe 409
                }

                it("deleting directory with recursive succeeds") {
                    api.mkdirServerFile(serverId, MkdirRequest(path = "/deep-dir/sub"))
                    api.writeServerFile(serverId, path = "/deep-dir/sub/data.txt", body = "data")
                    api.deleteServerFile(serverId, path = "/deep-dir", recursive = true)
                    val files = api.listServerFiles(serverId)
                    files.propertyEntries.map { it.name } shouldNotContain "deep-dir"
                }

                it("moves a file between directories") {
                    api.writeServerFile(serverId, path = "/source.txt", body = "move me")
                    api.mkdirServerFile(serverId, MkdirRequest(path = "/dest"))
                    api.moveServerFile(
                        serverId,
                        MoveRequest(sourcePath = "/source.txt", destinationPath = "/dest/source.txt")
                    )
                    val result = api.readServerFile(serverId, path = "/dest/source.txt")
                    result.content shouldBe "move me"
                    shouldThrow<Exception> {
                        api.readServerFile(serverId, path = "/source.txt")
                    }
                }

                it("renames a file in place") {
                    api.writeServerFile(serverId, path = "/old-name.txt", body = "rename test")
                    api.moveServerFile(
                        serverId,
                        MoveRequest(sourcePath = "/old-name.txt", destinationPath = "/new-name.txt")
                    )
                    val result = api.readServerFile(serverId, path = "/new-name.txt")
                    result.content shouldBe "rename test"
                    shouldThrow<Exception> {
                        api.readServerFile(serverId, path = "/old-name.txt")
                    }
                }

                it("moving to existing path returns 409") {
                    api.writeServerFile(serverId, path = "/a.txt", body = "a")
                    api.writeServerFile(serverId, path = "/b.txt", body = "b")
                    val ex = shouldThrow<ClientException> {
                        api.moveServerFile(
                            serverId,
                            MoveRequest(sourcePath = "/a.txt", destinationPath = "/b.txt")
                        )
                    }
                    ex.statusCode shouldBe 409
                }

                it("copies a file to a new path") {
                    api.writeServerFile(serverId, path = "/original.txt", body = "copy me")
                    api.copyServerFile(
                        serverId,
                        CopyRequest(sourcePath = "/original.txt", destinationPath = "/copy.txt", recursive = false)
                    )
                    val original = api.readServerFile(serverId, path = "/original.txt")
                    val copy = api.readServerFile(serverId, path = "/copy.txt")
                    original.content shouldBe "copy me"
                    copy.content shouldBe "copy me"
                }

                it("copying non-existent file returns 404") {
                    val ex = shouldThrow<ClientException> {
                        api.copyServerFile(
                            serverId,
                            CopyRequest(sourcePath = "/ghost.txt", destinationPath = "/copy.txt", recursive = false)
                        )
                    }
                    ex.statusCode shouldBe 404
                }

                it("listing subdirectory returns entries") {
                    api.mkdirServerFile(serverId, MkdirRequest(path = "/sub"))
                    api.writeServerFile(serverId, path = "/sub/item.txt", body = "item")
                    val files = api.listServerFiles(serverId, path = "/sub")
                    files.propertyEntries.shouldHaveSize(1)
                    files.propertyEntries.first().name shouldBe "item.txt"
                }

                it("listing non-existent path returns 404") {
                    val ex = shouldThrow<ClientException> {
                        api.listServerFiles(serverId, path = "/nonexistent")
                    }
                    ex.statusCode shouldBe 404
                }

                it("read non-existent file returns 404") {
                    val ex = shouldThrow<ClientException> {
                        api.readServerFile(serverId, path = "/does-not-exist.txt")
                    }
                    ex.statusCode shouldBe 404
                }

                it("downloads an existing file") {
                    api.writeServerFile(serverId, path = "/download-me.txt", body = "download content")
                    val bytes = api.downloadServerFile(serverId, path = "/download-me.txt")
                    String(bytes.map { it.toByte() }.toByteArray(), StandardCharsets.UTF_8) shouldBe "download content"
                }

                it("download non-existent file returns 404") {
                    val ex = shouldThrow<ClientException> {
                        api.downloadServerFile(serverId, path = "/does-not-exist.txt")
                    }
                    ex.statusCode shouldBe 404
                }

                it("reads known server.properties file") {
                    val result = api.readServerFile(serverId, path = "/server.properties")
                    result.content shouldNotBe ""
                    result.encoding shouldBe "utf-8"
                }

                it("writes and reads binary content") {
                    api.writeServerFile(serverId, path = "/binary-data.bin", body = "AAECAwQFBgcICQ==")
                    val result = api.readServerFile(serverId, path = "/binary-data.bin")
                    result.content shouldBe "AAECAwQFBgcICQ=="
                }

                it("mkdir existing path is idempotent") {
                    api.mkdirServerFile(serverId, MkdirRequest(path = "/existing-dir"))
                    api.mkdirServerFile(serverId, MkdirRequest(path = "/existing-dir"))
                    val files = api.listServerFiles(serverId)
                    files.propertyEntries.count { it.name == "existing-dir" } shouldBe 1
                }

                it("copies a directory recursively") {
                    api.mkdirServerFile(serverId, MkdirRequest(path = "/src-dir/nested"))
                    api.writeServerFile(serverId, path = "/src-dir/file1.txt", body = "f1")
                    api.writeServerFile(serverId, path = "/src-dir/nested/file2.txt", body = "f2")
                    api.copyServerFile(
                        serverId,
                        CopyRequest(sourcePath = "/src-dir", destinationPath = "/dst-dir", recursive = true)
                    )
                    val dstRoot = api.listServerFiles(serverId, path = "/dst-dir")
                    dstRoot.propertyEntries.map { it.name } shouldContain "nested"
                    dstRoot.propertyEntries.map { it.name } shouldContain "file1.txt"
                    val dstNested = api.listServerFiles(serverId, path = "/dst-dir/nested")
                    dstNested.propertyEntries.first().name shouldBe "file2.txt"
                }
            }
        }
    }
}

private infix fun <T> List<T>.shouldNotContain(element: T) {
    if (element in this) throw AssertionError("Collection should not contain $element but it does")
}
