package io.craftpanel.agent.grpc

import com.google.protobuf.ByteString
import io.craftpanel.proto.*
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID

class BulkDataClientTest : FunSpec({

    test("uploads the file in ordered chunks with credentials on the first chunk") {
        val source = Files.createTempFile("bulk-upload", ".bin")
        Files.write(source, ByteArray(70_000) { (it % 251).toByte() })
        val received = mutableListOf<io.craftpanel.proto.BulkChunk>()
        val service = object : BulkDataServiceGrpcKt.BulkDataServiceCoroutineImplBase() {
            override suspend fun streamToMaster(requests: Flow<BulkChunk>): BulkTransferAck {
                received += requests.toList()
                return bulkTransferAck { success = true; sizeBytes = 70_000 }
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

    test("receives chunks into the destination atomically") {
        val root = Files.createTempDirectory("bulk-receive")
        val destination = root.resolve("nested/result.txt")
        val service = object : BulkDataServiceGrpcKt.BulkDataServiceCoroutineImplBase() {
            override fun receiveFromMaster(request: BulkTransferInit): Flow<BulkChunk> = flow {
                emit(bulkChunk { data = ByteString.copyFromUtf8("hello ") })
                emit(bulkChunk { data = ByteString.copyFromUtf8("world"); isLast = true })
            }
        }

        withChannel(service) { channel ->
            runBlocking { BulkDataClient(channel).receiveFromMaster("node-key", "transfer-3", destination) }
        }

        Files.readString(destination) shouldBe "hello world"
        root.toFile().deleteRecursively()
    }

    test("deletes the temporary file when the master signals an error") {
        val root = Files.createTempDirectory("bulk-receive-error")
        val destination = root.resolve("result.txt")
        Files.writeString(destination, "original")
        val service = object : BulkDataServiceGrpcKt.BulkDataServiceCoroutineImplBase() {
            override fun receiveFromMaster(request: BulkTransferInit): Flow<BulkChunk> = flow {
                emit(bulkChunk { data = ByteString.copyFromUtf8("partial") })
                emit(bulkChunk { errorMessage = "transfer failed" })
            }
        }

        withChannel(service) { channel ->
            runBlocking { BulkDataClient(channel).receiveFromMaster("node-key", "transfer-4", destination) }
        }

        Files.readString(destination) shouldBe "original"
        Files.list(root).use { paths -> paths.map { it.fileName.toString() }.toList() shouldContainExactly listOf("result.txt") }
        root.toFile().deleteRecursively()
    }
}) {
    companion object {
        private fun <T> withChannel(
            service: BulkDataServiceGrpcKt.BulkDataServiceCoroutineImplBase,
            block: (io.grpc.ManagedChannel) -> T
        ): T {
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
