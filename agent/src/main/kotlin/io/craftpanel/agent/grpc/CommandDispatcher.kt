package io.craftpanel.agent.grpc

import io.craftpanel.agent.grpc.handlers.BackupHandler
import io.craftpanel.agent.grpc.handlers.ContainerHandler
import io.craftpanel.agent.grpc.handlers.ConsoleHandler
import io.craftpanel.agent.grpc.handlers.FileHandler
import io.craftpanel.agent.grpc.handlers.MigrationHandler
import io.craftpanel.proto.MasterMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private typealias PayloadCase = MasterMessage.PayloadCase

class CommandDispatcher private constructor(
    private val entries: Map<PayloadCase, Entry>,
) {

    private val log = LoggerFactory.getLogger(CommandDispatcher::class.java)

    suspend fun dispatch(msg: MasterMessage, out: AgentOutbound, scope: CoroutineScope) {
        val entry = entries[msg.payloadCase] ?: run {
            log.warn("Unhandled master message: {}", msg.payloadCase)
            return
        }
        val guarded: suspend () -> Unit = {
            runCatching { entry.handle(msg, out) }
                .onFailure { if (it is CancellationException) throw it else log.error("Unexpected handler failure", it) }
        }
        when (entry.mode) {
            Mode.SYNC -> guarded()
            Mode.CONCURRENT -> scope.launch { guarded() }
        }
    }

    private enum class Mode { SYNC, CONCURRENT }

    private data class Entry(
        val mode: Mode,
        val handle: suspend (msg: MasterMessage, out: AgentOutbound) -> Unit,
    )

    companion object {
        operator fun invoke(
            container: ContainerHandler,
            backup: BackupHandler,
            migration: MigrationHandler,
            file: FileHandler,
            console: ConsoleHandler,
            bulkClient: BulkDataClient,
        ): CommandDispatcher = CommandDispatcher(
            buildMap {
                fun entry(mode: Mode, handle: suspend (msg: MasterMessage, out: AgentOutbound) -> Unit) = Entry(mode, handle)

                put(PayloadCase.START_CONTAINER, entry(Mode.CONCURRENT) { msg, out -> container.handleStart(msg.startContainer, out) })
                put(PayloadCase.STOP_CONTAINER, entry(Mode.CONCURRENT) { msg, out -> container.handleStop(msg.stopContainer, out) })
                put(PayloadCase.RESTART_CONTAINER, entry(Mode.CONCURRENT) { msg, out -> container.handleRestart(msg.restartContainer, out) })
                put(PayloadCase.REMOVE_CONTAINER, entry(Mode.SYNC) { msg, out -> container.handleRemove(msg.removeContainer, out) })
                put(PayloadCase.SHUTDOWN, entry(Mode.SYNC) { msg, out -> container.handleShutdown(msg.shutdown, out) })

                put(PayloadCase.TRIGGER_BACKUP, entry(Mode.CONCURRENT) { msg, out -> backup.handleTriggerBackup(msg.triggerBackup, out) })
                put(PayloadCase.DELETE_BACKUP, entry(Mode.CONCURRENT) { msg, out -> backup.handleDeleteBackup(msg.deleteBackup) })

                put(PayloadCase.PREPARE_RSYNC_RECEIVE, entry(Mode.CONCURRENT) { msg, out -> migration.handlePrepareRsyncReceive(msg.prepareRsyncReceive, out) })
                put(PayloadCase.START_RSYNC, entry(Mode.CONCURRENT) { msg, out -> migration.handleStartRsync(msg.startRsync, out) })
                put(PayloadCase.SEND_RCON, entry(Mode.CONCURRENT) { msg, out -> migration.handleSendRcon(msg.sendRcon) })

                put(PayloadCase.CONSOLE_ATTACH, entry(Mode.CONCURRENT) { msg, out -> console.handleConsoleAttach(msg.consoleAttach, out) })
                put(PayloadCase.CONSOLE_INPUT, entry(Mode.SYNC) { msg, out -> console.handleConsoleInput(msg.consoleInput) })
                put(PayloadCase.CONSOLE_DETACH, entry(Mode.SYNC) { msg, out -> console.handleConsoleDetach(msg.consoleDetach) })
                put(PayloadCase.FETCH_CONTAINER_LOGS, entry(Mode.CONCURRENT) { msg, out -> console.handleFetchContainerLogs(msg.fetchContainerLogs, out) })

                put(PayloadCase.LIST_FILES, entry(Mode.CONCURRENT) { msg, out -> file.handleListFiles(msg.listFiles, out) })
                put(PayloadCase.READ_FILE, entry(Mode.CONCURRENT) { msg, out -> file.handleReadFile(msg.readFile, out) })
                put(PayloadCase.WRITE_FILE, entry(Mode.CONCURRENT) { msg, out -> file.handleWriteFile(msg.writeFile, out) })
                put(PayloadCase.DELETE_FILE, entry(Mode.CONCURRENT) { msg, out -> file.handleDeleteFile(msg.deleteFile, out) })
                put(PayloadCase.MAKE_DIRECTORY, entry(Mode.CONCURRENT) { msg, out -> file.handleMakeDirectory(msg.makeDirectory, out) })
                put(PayloadCase.MOVE_FILE, entry(Mode.CONCURRENT) { msg, out -> file.handleMoveFile(msg.moveFile, out) })
                put(PayloadCase.COPY_FILE, entry(Mode.CONCURRENT) { msg, out -> file.handleCopyFile(msg.copyFile, out) })

                put(PayloadCase.DOWNLOAD_FILE, entry(Mode.CONCURRENT) { msg, out -> file.handleDownloadFile(msg.downloadFile, bulkClient, out) })
                put(PayloadCase.UPLOAD_FILE, entry(Mode.CONCURRENT) { msg, out -> file.handleUploadFile(msg.uploadFile, bulkClient, out) })
                put(PayloadCase.DOWNLOAD_BACKUP, entry(Mode.CONCURRENT) { msg, out -> file.handleDownloadBackup(msg.downloadBackup, bulkClient, out) })

                put(PayloadCase.REBUILD_SYMLINKS, entry(Mode.SYNC) { msg, out ->
                    container.rebuildServerSymlinks(msg.rebuildSymlinks.serversList)
                    backup.rebuildBackupSymlinks(msg.rebuildSymlinks.backupsList)
                })
            }
        )
    }
}