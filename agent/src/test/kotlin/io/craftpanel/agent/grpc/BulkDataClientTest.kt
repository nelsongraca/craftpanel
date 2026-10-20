package io.craftpanel.agent.grpc

import com.google.protobuf.ByteString
import io.craftpanel.proto.*
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID

class BulkDataClientTest :
    FunSpec({

        test("uploads the file in ordered chunks with credentials on the first chunk") {
            val source = Files.createTempFile("bulk-upload", ".bin")
            Files.write(source, ByteArray(70_000) { (it % 251).toByte() })
            val received = mutableListOf<io.craftpanel.proto.BulkChunk>()
            val service = object : BulkDataServiceGrpcKt.BulkDataServiceCoroutineImplBase() {
                override suspend fun streamToMaster(requests: Flow<BulkChunk>): BulkTransferAck {
                    received += requests.toList()
                    return bulkTransferAck {
                        success = true
                        sizeBytes = 70_000
                    }
                }
            }

            withChannel(service) { channel ->
                runBlocking { BulkDataClient(channel).uploadToMaster("node-key", "transfer-1", source) }
            }

            received.map { it.data.size() } shouldBe listOf(65_536, 4_464)
            received.map { it.isLast } shouldBe listOf(false, true)
            received.first().transferId shouldBe "transfer-1"
            received.first().nodeKey shouldBe "node-key"
            received.drop(1).all { it.transferId.isEmpty() && it.nodeKey.isEmpty() } shouldBe true
            source.toFile().delete()
        }

        test("does not fail when the source file is missing") {
            val source = Files.createTempFile("bulk-upload-missing", ".bin")
            Files.delete(source)

            withChannel(object : BulkDataServiceGrpcKt.BulkDataServiceCoroutineImplBase() {}) { channel ->
                runBlocking { BulkDataClient(channel).uploadToMaster("node-key", "transfer-2", source) }
            }
        }

        test("receives multiple framed files atomically, including nested and empty files") {
            val root = Files.createTempDirectory("bulk-receive")
            val service = object : BulkDataServiceGrpcKt.BulkDataServiceCoroutineImplBase() {
                override fun receiveFromMaster(request: BulkTransferInit): Flow<BulkChunk> = flow {
                    emit(
                        bulkChunk {
                            filePath = "/a.txt"
                            data = ByteString.copyFromUtf8("hello ")
                        }
                    )
                    emit(
                        bulkChunk {
                            data = ByteString.copyFromUtf8("world")
                            fileEnd = true
                        }
                    )
                    emit(
                        bulkChunk {
                            filePath = "/nested/b.txt"
                            data = ByteString.copyFromUtf8("second")
                            fileEnd = true
                        }
                    )
                    emit(
                        bulkChunk {
                            filePath = "/empty.txt"
                            fileEnd = true
                        }
                    )
                    emit(bulkChunk { isLast = true })
                }
            }

            val result = withChannel(service) { channel ->
                runBlocking { BulkDataClient(channel).receiveFilesFromMaster("node-key", "transfer-3", root) }
            }

            result.transferError shouldBe null
            result.files.map { it.path to it.success } shouldBe listOf(
                "/a.txt" to true,
                "/nested/b.txt" to true,
                "/empty.txt" to true
            )
            result.files.map { it.sizeBytes } shouldBe listOf(11L, 6L, 0L)
            Files.readString(root.resolve("a.txt")) shouldBe "hello world"
            Files.readString(root.resolve("nested/b.txt")) shouldBe "second"
            Files.size(root.resolve("empty.txt")) shouldBe 0L
            root.toFile().deleteRecursively()
        }

        test("a traversal entry fails on its own without abandoning the batch") {
            val root = Files.createTempDirectory("bulk-receive-traversal")
            val service = object : BulkDataServiceGrpcKt.BulkDataServiceCoroutineImplBase() {
                override fun receiveFromMaster(request: BulkTransferInit): Flow<BulkChunk> = flow {
                    emit(
                        bulkChunk {
                            filePath = "../escape.txt"
                            data = ByteString.copyFromUtf8("bad")
                            fileEnd = true
                        }
                    )
                    emit(
                        bulkChunk {
                            filePath = "/ok.txt"
                            data = ByteString.copyFromUtf8("good")
                            fileEnd = true
                        }
                    )
                    emit(bulkChunk { isLast = true })
                }
            }

            val result = withChannel(service) { channel ->
                runBlocking { BulkDataClient(channel).receiveFilesFromMaster("node-key", "transfer-5", root) }
            }

            result.files.map { it.success } shouldBe listOf(false, true)
            result.files[0].errorMessage shouldContain "Path traversal"
            Files.exists(root.resolveSibling("escape.txt")) shouldBe false
            Files.readString(root.resolve("ok.txt")) shouldBe "good"
            root.toFile().deleteRecursively()
        }

        test("keeps completed files and removes the open temp when the master signals an error") {
            val root = Files.createTempDirectory("bulk-receive-error")
            val service = object : BulkDataServiceGrpcKt.BulkDataServiceCoroutineImplBase() {
                override fun receiveFromMaster(request: BulkTransferInit): Flow<BulkChunk> = flow {
                    emit(
                        bulkChunk {
                            filePath = "/done.txt"
                            data = ByteString.copyFromUtf8("done")
                            fileEnd = true
                        }
                    )
                    emit(
                        bulkChunk {
                            filePath = "/partial.txt"
                            data = ByteString.copyFromUtf8("partial")
                        }
                    )
                    emit(bulkChunk { errorMessage = "transfer failed" })
                }
            }

            val result = withChannel(service) { channel ->
                runBlocking { BulkDataClient(channel).receiveFilesFromMaster("node-key", "transfer-4", root) }
            }

            result.transferError shouldContain "transfer failed"
            result.files.map { it.path to it.success } shouldBe listOf("/done.txt" to true, "/partial.txt" to false)
            Files.readString(root.resolve("done.txt")) shouldBe "done"
            Files.exists(root.resolve("partial.txt")) shouldBe false
            Files.list(root).use { paths ->
                paths.map { it.fileName.toString() }.toList() shouldContainExactly listOf("done.txt")
            }
            root.toFile().deleteRecursively()
        }
    }) {
    companion object {
        private fun <T> withChannel(service: BulkDataServiceGrpcKt.BulkDataServiceCoroutineImplBase, block: (io.grpc.ManagedChannel) -> T): T {
            val name = "bulk-data-${UUID.randomUUID()}"
            val server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(service)
                .build()
                .start()
            val channel = InProcessChannelBuilder.forName(name)
                .directExecutor()
                .build()
            return try {
                block(channel)
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
            }
        }
    }
}
