package io.craftpanel.agent.grpc.handlers

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.proto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

class BackupHandler(private val config: AgentConfig) {

    private val log = LoggerFactory.getLogger(BackupHandler::class.java)

    suspend fun handleTriggerBackup(cmd: TriggerBackupCommand, out: AgentOutbound) {
        val sourceDir = "${config.dataBasePath}/servers/${cmd.serverId}"
        val destPath = "${config.dataBasePath}/backups/${cmd.backupId}.tar.gz"
        log.info("Backup ${cmd.backupId}: starting for server ${cmd.serverId} → $destPath")

        out.send {
            backupProgress = backupProgressUpdate {
                backupId = cmd.backupId
                serverId = cmd.serverId
                percentComplete = 0
            }
        }

        runCatching {
            withContext(Dispatchers.IO) {
                val destFile = File(destPath)
                destFile.parentFile?.mkdirs()

                val process = ProcessBuilder("tar", "-czf", destPath, "-C", sourceDir, ".")
                    .redirectErrorStream(true)
                    .start()

                out.send {
                    backupProgress = backupProgressUpdate {
                        backupId = cmd.backupId
                        serverId = cmd.serverId
                        percentComplete = 50
                    }
                }

                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    val output = process.inputStream.bufferedReader().readText()
                    error("tar exited with $exitCode: $output")
                }

                File(destPath).length()
            }
        }.onSuccess { sizeBytes ->
            log.info("Backup ${cmd.backupId}: completed, size=$sizeBytes bytes")
            out.send {
                backupComplete = backupCompleteUpdate {
                    backupId = cmd.backupId
                    serverId = cmd.serverId
                    success = true
                    filePath = destPath
                    this.sizeBytes = sizeBytes
                    completedAt = nowTimestamp()
                }
            }
        }.onFailure { ex ->
            log.error("Backup ${cmd.backupId}: failed", ex)
            out.send {
                backupComplete = backupCompleteUpdate {
                    backupId = cmd.backupId
                    serverId = cmd.serverId
                    success = false
                    errorMessage = ex.message ?: "Unknown error"
                    completedAt = nowTimestamp()
                }
            }
        }
    }

    suspend fun handleDeleteBackup(cmd: DeleteBackupCommand) {
        log.info("Deleting backup file ${cmd.filePath}")
        runCatching {
            withContext(Dispatchers.IO) { File(cmd.filePath).delete() }
        }.onFailure { log.error("Failed to delete backup file ${cmd.filePath}", it) }
    }
}
