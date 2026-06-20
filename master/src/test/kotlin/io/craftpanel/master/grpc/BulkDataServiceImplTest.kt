package io.craftpanel.master.grpc

import com.google.protobuf.ByteString
import io.craftpanel.proto.bulkChunk
import io.craftpanel.proto.bulkTransferInit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class BulkDataServiceImplTest : FunSpec({

    fun service(nodeKeyValid: Boolean = true): BulkDataServiceImpl {
        val control = mockk<ControlServiceImpl>()
        every { control.verifyNodeKey(any()) } returns nodeKeyValid
        return BulkDataServiceImpl(control)
    }

    // ── download path (agent → master) ────────────────────────────────────

    test("streamToMaster delivers chunks to registered download flow") {
        val svc = service()
        val transferId = "dl-001"
        val downloadFlow = svc.registerDownload(transferId)

        val chunks = flow {
            emit(bulkChunk {
                this.transferId = transferId
                nodeKey = "valid-key"
                data = ByteString.copyFromUtf8("hello")
                isLast = false
            })
            emit(bulkChunk {
                this.transferId = transferId
                nodeKey = "valid-key"
                data = ByteString.copyFromUtf8(" world")
                isLast = true
            })
        }

        val received = mutableListOf<ByteArray>()
        runTest {
            launch { svc.streamToMaster(chunks) }
            downloadFlow.toList(received)
        }

        received.size shouldBe 2
        String(received[0]) shouldBe "hello"
        String(received[1]) shouldBe " world"
    }

    test("streamToMaster with unknown transferId returns ack with success=true but no bytes delivered") {
        val svc = service()

        val chunks = flow {
            emit(bulkChunk {
                transferId = "no-such-id"
                nodeKey = "valid-key"
                data = ByteString.copyFromUtf8("data")
                isLast = true
            })
        }

        runTest {
            val ack = svc.streamToMaster(chunks)
            // no pending download registered — ack still returns (error path)
            ack.success shouldBe true
        }
    }

    test("streamToMaster with invalid node key still returns ack") {
        val svc = service(nodeKeyValid = false)
        val transferId = "dl-002"
        svc.registerDownload(transferId)

        val chunks = flow {
            emit(bulkChunk {
                this.transferId = transferId
                nodeKey = "bad-key"
                data = ByteString.copyFromUtf8("secret")
                isLast = true
            })
        }

        runTest {
            val ack = svc.streamToMaster(chunks)
            ack.success shouldBe true // ack always returned; download channel closed with error
        }
    }

    test("cancelDownload closes the download flow") {
        val svc = service()
        val transferId = "dl-cancel"
        val downloadFlow = svc.registerDownload(transferId)

        val collected = mutableListOf<ByteArray>()
        runTest {
            val job = launch { downloadFlow.toList(collected) }
            svc.cancelDownload(transferId)
            job.join()
        }

        collected.isEmpty() shouldBe true
    }

    // ── upload path (master → agent) ──────────────────────────────────────

    test("receiveFromMaster emits registered upload chunks and final isLast chunk") {
        val svc = service()
        val transferId = "ul-001"
        val channel = svc.registerUpload(transferId)

        channel.send("chunk1".toByteArray())
        channel.send("chunk2".toByteArray())
        channel.close()

        val init = bulkTransferInit {
            this.transferId = transferId
            nodeKey = "valid-key"
        }

        runTest {
            val chunks = svc.receiveFromMaster(init)
                .toList()
            chunks.size shouldBe 3 // 2 data + 1 final
            String(chunks[0].data.toByteArray()) shouldBe "chunk1"
            String(chunks[1].data.toByteArray()) shouldBe "chunk2"
            chunks[2].isLast shouldBe true
        }
    }

    test("receiveFromMaster with invalid node key throws StatusException") {
        val svc = service(nodeKeyValid = false)
        val init = bulkTransferInit {
            transferId = "ul-002"
            nodeKey = "bad-key"
        }

        runTest {
            val ex = io.kotest.assertions.throwables.shouldThrow<io.grpc.StatusException> {
                svc.receiveFromMaster(init)
                    .toList()
            }
            ex.status.code shouldBe io.grpc.Status.UNAUTHENTICATED.code
        }
    }

    test("receiveFromMaster with unknown transferId throws StatusException") {
        val svc = service()
        val init = bulkTransferInit {
            transferId = "no-such-upload"
            nodeKey = "valid-key"
        }

        runTest {
            val ex = io.kotest.assertions.throwables.shouldThrow<io.grpc.StatusException> {
                svc.receiveFromMaster(init)
                    .toList()
            }
            ex.status.code shouldBe io.grpc.Status.NOT_FOUND.code
        }
    }
})
