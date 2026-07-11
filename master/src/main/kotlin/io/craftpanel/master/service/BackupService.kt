package io.craftpanel.master.service

import io.craftpanel.master.domain.BackupStatus
import io.craftpanel.master.domain.BackupTrigger
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.service.repo.*
import io.craftpanel.proto.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val CRON_REGEX = Regex("""^(\*|[0-9,\-*/]+)\s+(\*|[0-9,\-*/]+)\s+(\*|[0-9,\-*/]+)\s+(\*|[0-9,\-*/]+)\s+(\*|[0-9,\-*/]+)$""")

@Serializable
data class BackupResponse(
    val id: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("node_id") val nodeId: String,
    val trigger: BackupTrigger,
    val status: BackupStatus,
    @SerialName("file_path") val filePath: String?,
    @SerialName("size_bytes") val sizeBytes: Long?,
    @SerialName("error_message") val errorMessage: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") val completedAt: String?
)

@Serializable
data class BackupScheduleResponse(@SerialName("backup_schedule") val backupSchedule: String?, @SerialName("backup_max_count") val backupMaxCount: Int)

@Serializable
data class PutBackupScheduleRequest(@SerialName("backup_schedule") val backupSchedule: String?, @SerialName("backup_max_count") val backupMaxCount: Int? = null)

data class BackupDownloadInfo(val serverId: Uuid, val backupId: String)

class BackupService(private val gateway: AgentGateway, private val dataServiceProxy: DataServiceProxy, private val serverRepository: ServerRepository, private val backupRepository: BackupRepository) {

    private val log = org.slf4j.LoggerFactory.getLogger(BackupService::class.java)

    fun listBackups(serverId: Uuid): List<BackupResponse> = backupRepository.listBackups(serverId)
        .map { it.toResponse() }

    fun triggerBackup(serverId: Uuid, trigger: BackupTrigger = BackupTrigger.MANUAL): BackupResponse {
        val serverRow = serverRepository.findById(serverId) ?: throw NotFoundException("Server not found")
        val nodeId = serverRow.nodeId.toString()
        val now = Clock.System.now()

        val maxCount = serverRow.backupMaxCount
        val toRotate = backupRepository.findOldestCompletedBackups(serverId, maxCount)
        for (old in toRotate) {
            if (!old.filePath.isNullOrEmpty()) {
                val sent = gateway.sendToNode(
                    old.nodeId.toString(),
                    masterMessage {
                        deleteBackup = deleteBackupCommand {
                            backupId = old.id.toString()
                            this.filePath = old.filePath
                        }
                    }
                )
                if (!sent) {
                    log.warn("Could not send deleteBackup to node ${old.nodeId} for backup ${old.id} — skipping row deletion")
                    continue
                }
            }
            backupRepository.deleteBackup(old.id)
        }

        val backup = backupRepository.createBackup(serverId, serverRow.nodeId, trigger)

        val sent = gateway.sendToNode(
            nodeId,
            masterMessage {
                triggerBackup = triggerBackupCommand {
                    this.backupId = backup.id.toString()
                    this.serverId = serverId.toString()
                    containerName = "craftpanel-$serverId"
                }
            }
        )

        if (!sent) {
            backupRepository.updateBackupStatus(backup.id, BackupStatus.FAILED, null, null, "Agent not connected", now)
            throw BadGatewayException("Agent not connected")
        }

        return backupRepository.findBackupById(backup.id)!!
            .toResponse()
    }

    fun deleteBackup(serverId: Uuid, backupId: Uuid) {
        val backup = backupRepository.findBackupById(backupId)
            ?.takeIf { it.serverId == serverId }
            ?: throw NotFoundException("Backup not found")
        if (backup.status == "IN_PROGRESS") throw ConflictException("Cannot delete a backup that is in progress")
        if (!backup.filePath.isNullOrEmpty()) {
            gateway.sendToNode(
                backup.nodeId.toString(),
                masterMessage {
                    deleteBackup = deleteBackupCommand {
                        this.backupId = backupId.toString()
                        this.filePath = backup.filePath
                    }
                }
            )
        }
        backupRepository.deleteBackup(backupId)
    }

    fun resolveDownload(serverId: Uuid, backupId: Uuid): BackupDownloadInfo {
        val backup = backupRepository.findBackupById(backupId)
            ?.takeIf { it.serverId == serverId }
            ?: throw NotFoundException("Backup not found")
        if (backup.status != "COMPLETED") throw ConflictException("Backup is not in COMPLETED status")
        return BackupDownloadInfo(serverId = serverId, backupId = backupId.toString())
    }

    suspend fun downloadStream(info: BackupDownloadInfo) = dataServiceProxy.downloadBackup(info.serverId, info.backupId)

    fun getSchedule(serverId: Uuid): BackupScheduleResponse {
        val serverRow = serverRepository.findById(serverId) ?: throw NotFoundException("Server not found")
        return BackupScheduleResponse(
            backupSchedule = serverRow.backupSchedule,
            backupMaxCount = serverRow.backupMaxCount
        )
    }

    fun updateSchedule(serverId: Uuid, req: PutBackupScheduleRequest) {
        if (req.backupSchedule != null && !CRON_REGEX.matches(req.backupSchedule)) {
            throw UnprocessableException("Invalid cron expression")
        }
        if (req.backupMaxCount != null && req.backupMaxCount < 1) {
            throw UnprocessableException("backup_max_count must be at least 1")
        }
        serverRepository.updateBackupSchedule(serverId, req.backupSchedule, req.backupMaxCount)
    }
}

private fun BackupRow.toResponse() = BackupResponse(
    id = id.toString(),
    serverId = serverId.toString(),
    nodeId = nodeId.toString(),
    trigger = BackupTrigger.fromDb(trigger),
    status = BackupStatus.fromDb(status),
    filePath = filePath,
    sizeBytes = sizeBytes,
    errorMessage = errorMessage,
    createdAt = createdAt,
    completedAt = completedAt
)
