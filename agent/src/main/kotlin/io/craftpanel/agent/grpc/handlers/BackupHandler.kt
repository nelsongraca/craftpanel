package io.craftpanel.agent.grpc.handlers

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.proto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
                File(destPath).parentFile?.mkdirs()

                val totalBytes = runCatching {
                    val duProcess = ProcessBuilder("du", "-sb", sourceDir)
                        .redirectErrorStream(true)
                        .start()
                    val output = duProcess.inputStream.bufferedReader()
                        .readText()
                        .trim()
                    duProcess.waitFor()
                    output.split("\t")
                        .firstOrNull()
                        ?.toLongOrNull() ?: 0L
                }.getOrDefault(0L)

                log.debug("Backup ${cmd.backupId}: source size estimate = $totalBytes bytes")

                coroutineScope {
                    val tarPb = ProcessBuilder("tar", "-c", "-C", sourceDir, ".")
                    val pvPb = ProcessBuilder("pv", "-n", "-s", totalBytes.toString())
                    val gzipPb = ProcessBuilder("gzip")
                        .redirectOutput(File(destPath))

                    val procs = ProcessBuilder.startPipeline(listOf(tarPb, pvPb, gzipPb))
                    val pvProc = procs[1]

                    val stderrReader = launch(Dispatchers.IO) {
                        pvProc.errorStream.bufferedReader()
                            .lineSequence()
                            .forEach { line ->
                                val percent = line.trim()
                                    .toIntOrNull()
                                if (percent != null) {
                                    out.trySend {
                                        backupProgress = backupProgressUpdate {
                                            backupId = cmd.backupId
                                            serverId = cmd.serverId
                                            percentComplete = percent
                                        }
                                    }
                                }
                            }
                    }

                    val exitCodes = procs.map { it.waitFor() }
                    stderrReader.join()

                    if (exitCodes.any { it != 0 }) {
                        error("backup pipeline failed: tar=${exitCodes[0]} pv=${exitCodes[1]} gzip=${exitCodes[2]}")
                    }
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
        }
            .onFailure { ex ->
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
