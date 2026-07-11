package io.craftpanel.agent.grpc.handlers

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.agent.grpc.BulkDataClient
import io.craftpanel.proto.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.*

class FileHandlerTest :
    FunSpec({
        test("classifyFileError maps NoSuchFileException to NOT_FOUND") {
            classifyFileError(NoSuchFileException("/path")) shouldBe ErrorCode.NOT_FOUND
        }

        test("classifyFileError maps FileAlreadyExistsException to ALREADY_EXISTS") {
            classifyFileError(FileAlreadyExistsException("/path")) shouldBe ErrorCode.ALREADY_EXISTS
        }

        test("classifyFileError maps DirectoryNotEmptyException to CONFLICT") {
            classifyFileError(DirectoryNotEmptyException("/path")) shouldBe ErrorCode.CONFLICT
        }

        test("classifyFileError maps AccessDeniedException to PERMISSION_DENIED") {
            classifyFileError(AccessDeniedException("/path")) shouldBe ErrorCode.PERMISSION_DENIED
        }

        test("classifyFileError maps IOException to INTERNAL") {
            classifyFileError(IOException("disk error")) shouldBe ErrorCode.INTERNAL
        }

        test("classifyFileError maps generic exception to INTERNAL") {
            classifyFileError(RuntimeException("something broke")) shouldBe ErrorCode.INTERNAL
        }

        test("classifyFileError maps IllegalArgumentException to INTERNAL") {
            classifyFileError(IllegalArgumentException("bad arg")) shouldBe ErrorCode.INTERNAL
        }

        // ── Handler failure envelopes ───────────────────────────────────────────

        lateinit var dataDir: File

        beforeTest {
            dataDir = kotlin.io.path.createTempDirectory("file-handler-test")
                .toFile()
        }

        afterTest {
            dataDir.deleteRecursively()
        }

        fun config() = AgentConfig(
            profile = "dev",
            masterAddress = "localhost",
            masterPort = 50051,
            masterHttpPort = 80,
            tlsCertPath = "",
            caCertFilePath = "/etc/craftpanel/grpc-ca.crt",
            bootstrapToken = "test-token-16chars",
            keyFilePath = "/etc/craftpanel/node.key",
            dockerSocketPath = "unix:///var/run/docker.sock",
            agentVersion = "test",
            dataBasePath = dataDir.absolutePath,
            hostDataBasePath = dataDir.absolutePath,
            mcRouterImage = "itzg/mc-router:latest",
            mcRouterUpdateOnStart = false,
            mcRouterContainerName = "",
            publicIpUrl = "",
            hostnameOverride = "",
            systemReservedRamMb = 0,
            craftpanelNetwork = "craftpanel",
            containerNamePrefix = "craftpanel",
            privateIpOverride = "",
            metricsPollIntervalSeconds = 60
        )

        fun newOutbound(): Pair<AgentOutbound, Channel<AgentMessage>> {
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)
            return AgentOutbound(channel, "node-1") to channel
        }

        test("handleListFiles reports NOT_FOUND when the requested path escapes the server root") {
            val handler = FileHandler(config(), "node-key")
            val (out, channel) = newOutbound()

            runBlocking {
                handler.handleListFiles(
                    listFilesRequest {
                        requestId = "r1"
                        serverId = "missing-server"
                        path = "/../../etc"
                    },
                    out
                )
            }

            val response = channel.tryReceive()
                .getOrThrow()
                .listFilesResponse
            response.errorCode shouldBe ErrorCode.INTERNAL
            response.errorMessage shouldBe "Path traversal detected"
        }

        test("handleReadFile reports NOT_FOUND when the file does not exist") {
            val handler = FileHandler(config(), "node-key")
            val (out, channel) = newOutbound()

            runBlocking {
                handler.handleReadFile(
                    readFileRequest {
                        requestId = "r1"
                        serverId = "srv-1"
                        path = "missing.txt"
                    },
                    out
                )
            }

            val response = channel.tryReceive()
                .getOrThrow()
                .readFileResponse
            response.errorCode shouldBe ErrorCode.NOT_FOUND
            response.errorMessage shouldContain "File not found"
        }

        test("handleWriteFile reports failure envelope on write error") {
            val handler = FileHandler(config(), "node-key")
            val (out, channel) = newOutbound()

            runBlocking {
                handler.handleWriteFile(
                    writeFileRequest {
                        requestId = "r1"
                        serverId = "srv-1"
                        path = "../escape.txt"
                    },
                    out
                )
            }

            val response = channel.tryReceive()
                .getOrThrow()
                .writeFileResponse
            response.success shouldBe false
            response.errorCode shouldBe ErrorCode.INTERNAL
            response.errorMessage shouldBe "Path traversal detected"
        }

        test("handleDeleteFile reports NOT_FOUND when the path does not exist") {
            val handler = FileHandler(config(), "node-key")
            val (out, channel) = newOutbound()

            runBlocking {
                handler.handleDeleteFile(
                    deleteFileRequest {
                        requestId = "r1"
                        serverId = "srv-1"
                        path = "missing.txt"
                    },
                    out
                )
            }

            val response = channel.tryReceive()
                .getOrThrow()
                .deleteFileResponse
            response.success shouldBe false
            response.errorCode shouldBe ErrorCode.NOT_FOUND
            response.errorMessage shouldContain "Path not found"
        }

        test("handleMakeDirectory reports failure envelope on path traversal") {
            val handler = FileHandler(config(), "node-key")
            val (out, channel) = newOutbound()

            runBlocking {
                handler.handleMakeDirectory(
                    makeDirectoryRequest {
                        requestId = "r1"
                        serverId = "srv-1"
                        path = "../escape"
                    },
                    out
                )
            }

            val response = channel.tryReceive()
                .getOrThrow()
                .makeDirectoryResponse
            response.success shouldBe false
            response.errorCode shouldBe ErrorCode.INTERNAL
            response.errorMessage shouldBe "Path traversal detected"
        }

        test("handleMoveFile reports NOT_FOUND when the source does not exist") {
            val handler = FileHandler(config(), "node-key")
            val (out, channel) = newOutbound()

            runBlocking {
                handler.handleMoveFile(
                    moveFileRequest {
                        requestId = "r1"
                        serverId = "srv-1"
                        sourcePath = "missing.txt"
                        destinationPath = "dest.txt"
                    },
                    out
                )
            }

            val response = channel.tryReceive()
                .getOrThrow()
                .moveFileResponse
            response.success shouldBe false
            response.errorCode shouldBe ErrorCode.NOT_FOUND
            response.errorMessage shouldContain "Source not found"
        }

        test("handleCopyFile reports NOT_FOUND when the source does not exist") {
            val handler = FileHandler(config(), "node-key")
            val (out, channel) = newOutbound()

            runBlocking {
                handler.handleCopyFile(
                    copyFileRequest {
                        requestId = "r1"
                        serverId = "srv-1"
                        sourcePath = "missing.txt"
                        destinationPath = "dest.txt"
                    },
                    out
                )
            }

            val response = channel.tryReceive()
                .getOrThrow()
                .copyFileResponse
            response.success shouldBe false
            response.errorCode shouldBe ErrorCode.NOT_FOUND
            response.errorMessage shouldContain "Source not found"
        }

        test("handleDownloadFile reports NOT_FOUND when the file does not exist") {
            val handler = FileHandler(config(), "node-key")
            val (out, channel) = newOutbound()
            val bulkClient = mockk<BulkDataClient>(relaxed = true)

            runBlocking {
                handler.handleDownloadFile(
                    downloadFileCommand {
                        requestId = "r1"
                        serverId = "srv-1"
                        path = "missing.txt"
                        transferId = "t1"
                    },
                    bulkClient,
                    out
                )
            }

            val response = channel.tryReceive()
                .getOrThrow()
                .downloadFileResponse
            response.success shouldBe false
            response.errorCode shouldBe ErrorCode.NOT_FOUND
            response.errorMessage shouldContain "File not found: missing.txt"
        }

        test("handleUploadFile reports failure envelope when the bulk transfer fails") {
            val handler = FileHandler(config(), "node-key")
            val (out, channel) = newOutbound()
            val bulkClient = mockk<BulkDataClient>()
            coEvery { bulkClient.receiveFromMaster(any(), any(), any()) } throws IOException("transfer failed")

            runBlocking {
                handler.handleUploadFile(
                    uploadFileCommand {
                        requestId = "r1"
                        serverId = "srv-1"
                        path = "dest.txt"
                        transferId = "t1"
                    },
                    bulkClient,
                    out
                )
            }

            val response = channel.tryReceive()
                .getOrThrow()
                .uploadFileResponse
            response.success shouldBe false
            response.errorCode shouldBe ErrorCode.INTERNAL
            response.errorMessage shouldBe "transfer failed"
        }

        test("handleDownloadBackup reports INTERNAL when the backup id is invalid") {
            val handler = FileHandler(config(), "node-key")
            val (out, channel) = newOutbound()
            val bulkClient = mockk<BulkDataClient>(relaxed = true)

            runBlocking {
                handler.handleDownloadBackup(
                    downloadBackupCommand {
                        requestId = "r1"
                        backupId = "not-a-uuid"
                        transferId = "t1"
                    },
                    bulkClient,
                    out
                )
            }

            val response = channel.tryReceive()
                .getOrThrow()
                .downloadFileResponse
            response.success shouldBe false
            response.errorCode shouldBe ErrorCode.INTERNAL
            response.errorMessage shouldBe "Invalid backup id"
        }
    })
