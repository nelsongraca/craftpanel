package io.craftpanel.master.grpc

import io.craftpanel.master.createTestAgentDataOps
import io.craftpanel.master.service.BadGatewayException
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.ServerView
import io.craftpanel.proto.MasterMessage
import io.craftpanel.proto.agentMessage
import io.craftpanel.proto.uploadFilesResponse
import io.craftpanel.proto.uploadedFileResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class DataServiceProxyTest :
    FunSpec({

        fun serverView(nodeId: Uuid): ServerView = mockk {
            every { this@mockk.nodeId } returns nodeId
            every { status } returns "RUNNING"
        }

        test("uploadFiles streams frames and maps the agent's per-file results") {
            val serverId = Uuid.random()
            val nodeId = Uuid.random()
            val repo = mockk<ServerRepository> {
                every { findById(serverId) } returns serverView(nodeId)
            }

            val bulk = mockk<BulkDataServiceImpl>()
            val channel = Channel<UploadFrame>(16)
            every { bulk.registerUpload(any()) } returns channel
            every { bulk.cancelUpload(any()) } returns Unit

            val context = DataOpContext(ConcurrentHashMap(), ConcurrentHashMap())
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            var sentCommand: MasterMessage? = null

            val dataOps = createTestAgentDataOps(
                dataOpContext = context,
                sendToNode = { targetNode, msg ->
                    sentCommand = msg
                    scope.launch {
                        // Drain the bounded channel exactly like the real gRPC pump consumer.
                        for (frame in channel) {
                            // drain
                        }
                        context.pendingRequests["$targetNode/${msg.uploadFiles.requestId}"]
                            ?.complete(
                                agentMessage {
                                    uploadFilesResponse = uploadFilesResponse {
                                        requestId = msg.uploadFiles.requestId
                                        files.add(
                                            uploadedFileResult {
                                                path = "/a.txt"
                                                success = true
                                                sizeBytes = 5
                                            }
                                        )
                                        files.add(
                                            uploadedFileResult {
                                                path = "/bad.txt"
                                                success = false
                                                errorMessage = "Path traversal detected"
                                            }
                                        )
                                    }
                                }
                            )
                    }
                    true
                }
            )

            val proxy = DataServiceProxy(dataOps, bulk, repo, uploadTimeoutMs = 5_000)
            val result = runBlocking {
                proxy.uploadFiles(
                    serverId,
                    flow {
                        emit(UploadFrame.FileStart("/a.txt"))
                        emit(UploadFrame.Data("hello".toByteArray()))
                        emit(UploadFrame.FileEnd)
                        emit(UploadFrame.FileStart("/bad.txt"))
                        emit(UploadFrame.FileEnd)
                    }
                )
            }

            result shouldBe listOf(
                FileUploadResult("/a.txt", success = true, sizeBytes = 5),
                FileUploadResult("/bad.txt", success = false, sizeBytes = 0, errorMessage = "Path traversal detected")
            )
            sentCommand?.uploadFiles?.serverId shouldBe serverId.toString()
            sentCommand?.uploadFiles?.transferId.isNullOrEmpty() shouldBe false
            scope.cancel()
        }

        test("uploadFiles surfaces a transfer-level error reported by the agent") {
            val serverId = Uuid.random()
            val nodeId = Uuid.random()
            val repo = mockk<ServerRepository> {
                every { findById(serverId) } returns serverView(nodeId)
            }

            val bulk = mockk<BulkDataServiceImpl>()
            val channel = Channel<UploadFrame>(16)
            every { bulk.registerUpload(any()) } returns channel
            every { bulk.cancelUpload(any()) } returns Unit

            val context = DataOpContext(ConcurrentHashMap(), ConcurrentHashMap())
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val dataOps = createTestAgentDataOps(
                dataOpContext = context,
                sendToNode = { targetNode, msg ->
                    scope.launch {
                        for (frame in channel) {
                            // drain
                        }
                        context.pendingRequests["$targetNode/${msg.uploadFiles.requestId}"]
                            ?.complete(
                                agentMessage {
                                    uploadFilesResponse = uploadFilesResponse {
                                        requestId = msg.uploadFiles.requestId
                                        transferError = "disk full"
                                    }
                                }
                            )
                    }
                    true
                }
            )

            val proxy = DataServiceProxy(dataOps, bulk, repo, uploadTimeoutMs = 5_000)
            val error = runCatching {
                runBlocking {
                    proxy.uploadFiles(
                        serverId,
                        flow {
                            emit(UploadFrame.FileStart("/a.txt"))
                            emit(UploadFrame.FileEnd)
                        }
                    )
                }
            }.exceptionOrNull()

            (error is BadGatewayException) shouldBe true
            error?.message shouldBe "disk full"
            scope.cancel()
        }

        test("uploadFiles cancels the pending transfer when the agent never responds") {
            val serverId = Uuid.random()
            val nodeId = Uuid.random()
            val repo = mockk<ServerRepository> {
                every { findById(serverId) } returns serverView(nodeId)
            }
            val bulk = mockk<BulkDataServiceImpl>()
            val channel = Channel<UploadFrame>(16)
            every { bulk.registerUpload(any()) } returns channel
            val cancelled = mutableListOf<String>()
            every { bulk.cancelUpload(capture(cancelled)) } returns Unit

            val dataOps = createTestAgentDataOps(sendToNode = { _, _ -> true })
            val proxy = DataServiceProxy(dataOps, bulk, repo, uploadTimeoutMs = 50)

            runCatching {
                runBlocking {
                    proxy.uploadFiles(
                        serverId,
                        flow {
                            emit(UploadFrame.FileStart("/a.txt"))
                            emit(UploadFrame.FileEnd)
                        }
                    )
                }
            }

            cancelled.size shouldBe 1
        }
    })
