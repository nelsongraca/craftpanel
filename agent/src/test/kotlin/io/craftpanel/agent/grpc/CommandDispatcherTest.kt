package io.craftpanel.agent.grpc

import io.craftpanel.agent.grpc.handlers.BackupHandler
import io.craftpanel.agent.grpc.handlers.ContainerHandler
import io.craftpanel.agent.grpc.handlers.ConsoleHandler
import io.craftpanel.agent.grpc.handlers.FileHandler
import io.craftpanel.agent.grpc.handlers.MigrationHandler
import io.craftpanel.proto.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommandDispatcherTest :
    FunSpec({
        val out = mockk<AgentOutbound>(relaxed = true)

        val container = mockk<ContainerHandler>(relaxed = true)
        val backup = mockk<BackupHandler>(relaxed = true)
        val migration = mockk<MigrationHandler>(relaxed = true)
        val file = mockk<FileHandler>(relaxed = true)
        val console = mockk<ConsoleHandler>(relaxed = true)
        val bulkClient = mockk<BulkDataClient>(relaxed = true)

        val dispatcher = CommandDispatcher(
            container = container,
            backup = backup,
            migration = migration,
            file = file,
            console = console,
            bulkClient = bulkClient,
        )

        test("routes container lifecycle commands to ContainerHandler") {
            runTest {
                dispatcher.dispatch(masterMessage { startContainer = startContainerCommand {} }, out, this)
                dispatcher.dispatch(masterMessage { stopContainer = stopContainerCommand {} }, out, this)
                dispatcher.dispatch(masterMessage { restartContainer = restartContainerCommand {} }, out, this)
                dispatcher.dispatch(masterMessage { removeContainer = removeContainerCommand {} }, out, this)
                dispatcher.dispatch(masterMessage { shutdown = shutdownCommand {} }, out, this)
                advanceUntilIdle()

                coVerify { container.handleStart(any(), out) }
                coVerify { container.handleStop(any(), out) }
                coVerify { container.handleRestart(any(), out) }
                coVerify { container.handleRemove(any(), out) }
                coVerify { container.handleShutdown(any(), out) }
            }
        }

        test("routes backup commands to BackupHandler") {
            runTest {
                dispatcher.dispatch(masterMessage { triggerBackup = triggerBackupCommand {} }, out, this)
                dispatcher.dispatch(masterMessage { deleteBackup = deleteBackupCommand {} }, out, this)
                advanceUntilIdle()

                coVerify { backup.handleTriggerBackup(any(), out) }
                coVerify { backup.handleDeleteBackup(any()) }
            }
        }

        test("routes migration commands to MigrationHandler") {
            runTest {
                dispatcher.dispatch(masterMessage { prepareRsyncReceive = prepareRsyncReceiveCommand {} }, out, this)
                dispatcher.dispatch(masterMessage { startRsync = startRsyncCommand {} }, out, this)
                dispatcher.dispatch(masterMessage { sendRcon = sendRconCommand {} }, out, this)
                advanceUntilIdle()

                coVerify { migration.handlePrepareRsyncReceive(any(), out) }
                coVerify { migration.handleStartRsync(any(), out) }
                coVerify { migration.handleSendRcon(any()) }
            }
        }

        test("routes console commands to ConsoleHandler") {
            runTest {
                dispatcher.dispatch(masterMessage { consoleAttach = consoleAttach {} }, out, this)
                dispatcher.dispatch(masterMessage { consoleInput = consoleInput {} }, out, this)
                dispatcher.dispatch(masterMessage { consoleDetach = consoleDetach {} }, out, this)
                dispatcher.dispatch(masterMessage { fetchContainerLogs = fetchContainerLogsRequest {} }, out, this)
                advanceUntilIdle()

                coVerify { console.handleConsoleAttach(any(), out) }
                verify { console.handleConsoleInput(any()) }
                verify { console.handleConsoleDetach(any()) }
                coVerify { console.handleFetchContainerLogs(any(), out) }
            }
        }

        test("routes unary file commands to FileHandler") {
            runTest {
                dispatcher.dispatch(masterMessage { listFiles = listFilesRequest {} }, out, this)
                dispatcher.dispatch(masterMessage { readFile = readFileRequest {} }, out, this)
                dispatcher.dispatch(masterMessage { writeFile = writeFileRequest {} }, out, this)
                dispatcher.dispatch(masterMessage { deleteFile = deleteFileRequest {} }, out, this)
                dispatcher.dispatch(masterMessage { makeDirectory = makeDirectoryRequest {} }, out, this)
                dispatcher.dispatch(masterMessage { moveFile = moveFileRequest {} }, out, this)
                dispatcher.dispatch(masterMessage { copyFile = copyFileRequest {} }, out, this)
                advanceUntilIdle()

                coVerify { file.handleListFiles(any(), out) }
                coVerify { file.handleReadFile(any(), out) }
                coVerify { file.handleWriteFile(any(), out) }
                coVerify { file.handleDeleteFile(any(), out) }
                coVerify { file.handleMakeDirectory(any(), out) }
                coVerify { file.handleMoveFile(any(), out) }
                coVerify { file.handleCopyFile(any(), out) }
            }
        }

        test("routes bulk file commands with bulk client") {
            runTest {
                dispatcher.dispatch(masterMessage { downloadFile = downloadFileCommand { requestId = "r" } }, out, this)
                dispatcher.dispatch(masterMessage { uploadFile = uploadFileCommand { requestId = "r" } }, out, this)
                dispatcher.dispatch(masterMessage { downloadBackup = downloadBackupCommand { requestId = "r" } }, out, this)
                advanceUntilIdle()

                coVerify { file.handleDownloadFile(any(), bulkClient, out) }
                coVerify { file.handleUploadFile(any(), bulkClient, out) }
                coVerify { file.handleDownloadBackup(any(), bulkClient, out) }
            }
        }

        test("routes rebuild_symlinks to both handler rebuild methods") {
            val builder = io.craftpanel.proto.RebuildSymlinksCommand.newBuilder()
            builder.addServersBuilder()
                .setServerId("s1")
                .setServerName("world")
            builder.addBackupsBuilder()
                .setBackupId("b1")
                .setServerId("s1")
                .setServerName("world")
                .setCreatedAtFormatted("2026-07-18_16-00-00")
                .setFilePath("/tmp/b1.tar.gz")
            val cmd = masterMessage { rebuildSymlinks = builder.build() }
            runTest {
                dispatcher.dispatch(cmd, out, this)

                coVerify { container.rebuildServerSymlinks(any()) }
                coVerify { backup.rebuildBackupSymlinks(any()) }
            }
        }

        test("unhandled message does not throw") {
            runTest {
                dispatcher.dispatch(masterMessage {}, out, this)
            }
        }

        test("SYNC entries run inline; CONCURRENT entries launch on scope") {
            var syncRan = false
            every { console.handleConsoleInput(any()) } answers { syncRan = true }

            var concurrentRan = false
            coEvery { container.handleStart(any(), any()) } answers { concurrentRan = true }

            runTest {
                dispatcher.dispatch(masterMessage { consoleInput = consoleInput {} }, out, this)
                syncRan shouldBe true

                dispatcher.dispatch(masterMessage { startContainer = startContainerCommand {} }, out, this)
                concurrentRan shouldBe false
                advanceUntilIdle()
                concurrentRan shouldBe true
            }
        }

        test("non-CancellationException in SYNC entry is swallowed") {
            every { console.handleConsoleInput(any()) } throws RuntimeException("boom")
            runTest {
                dispatcher.dispatch(masterMessage { consoleInput = consoleInput {} }, out, this)
            }
        }

        test("CancellationException propagates from SYNC entry") {
            coEvery { console.handleConsoleInput(any()) } throws CancellationException("cancel")
            shouldThrow<CancellationException> {
                runTest {
                    dispatcher.dispatch(masterMessage { consoleInput = consoleInput {} }, out, this)
                }
            }
        }

        test("handler exception in CONCURRENT entry does not escape dispatch") {
            coEvery { container.handleStart(any(), any()) } throws RuntimeException("boom")
            runTest {
                dispatcher.dispatch(masterMessage { startContainer = startContainerCommand {} }, out, this)
                advanceUntilIdle()

                dispatcher.dispatch(masterMessage { stopContainer = stopContainerCommand {} }, out, this)
                advanceUntilIdle()
            }
        }
    })
