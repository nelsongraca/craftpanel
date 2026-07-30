package io.craftpanel.master.service

import io.craftpanel.master.database.entity.BackupEntity
import io.craftpanel.master.database.entity.ServerEntity
import io.craftpanel.master.database.schema.Backups
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.BackupStatus
import io.craftpanel.master.domain.BackupTrigger
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.util.formatSymlinkTimestamp
import io.craftpanel.master.util.toUtcString
import io.craftpanel.proto.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
                val server = serverRepository.findById(old.serverId)
                val sent = gateway.sendToNode(
                    old.nodeId.toString(),
                    masterMessage {
                        deleteBackup = deleteBackupCommand {
                            backupId = old.id.toString()
                            this.filePath = old.filePath
                            this.serverId = old.serverId.toString()
                            this.serverName = server?.name ?: ""
                            this.createdAtFormatted = formatSymlinkTimestamp(old.createdAt)
                        }
                    }
                )
                if (!sent) {
                    log.warn("Could not send deleteBackup to node ${old.nodeId} for backup ${old.id} — skipping row deletion")
                    continue
                }
            }
            transaction { BackupEntity.findById(old.id)?.delete() }
        }

        val backup = transaction {
            val b = BackupEntity.new {
                this.serverId = EntityID(serverId, Servers)
                this.nodeId = EntityID(serverRow.nodeId, Nodes)
                this.trigger = trigger.name
                this.status = BackupStatus.IN_PROGRESS.name
            }
            val row = Backups.selectAll().where { Backups.id eq b.id }.first()
            BackupRow(
                id = row[Backups.id].value,
                serverId = row[Backups.serverId].value,
                nodeId = row[Backups.nodeId].value,
                trigger = row[Backups.trigger],
                status = row[Backups.status],
                filePath = row[Backups.filePath],
                sizeBytes = row[Backups.sizeBytes],
                errorMessage = row[Backups.errorMessage],
                createdAt = row[Backups.createdAt].toUtcString(),
                completedAt = row[Backups.completedAt]?.toUtcString()
            )
        }

        val sent = gateway.sendToNode(
            nodeId,
            masterMessage {
                triggerBackup = triggerBackupCommand {
                    this.backupId = backup.id.toString()
                    this.serverId = serverId.toString()
                    containerName = "craftpanel-$serverId"
                    serverName = serverRow.name
                    createdAtFormatted = formatSymlinkTimestamp(backup.createdAt)
                }
            }
        )

        if (!sent) {
            transaction {
            BackupEntity.findById(backup.id)?.let {
                it.status = BackupStatus.FAILED.name
                it.errorMessage = "Agent not connected"
                it.completedAt = now.toLocalDateTime(TimeZone.UTC)
            }
        }
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
            val server = serverRepository.findById(backup.serverId)
            gateway.sendToNode(
                backup.nodeId.toString(),
                masterMessage {
                    deleteBackup = deleteBackupCommand {
                        this.backupId = backupId.toString()
                        this.filePath = backup.filePath
                        this.serverId = backup.serverId.toString()
                        this.serverName = server?.name ?: ""
                        this.createdAtFormatted = formatSymlinkTimestamp(backup.createdAt)
                    }
                }
            )
        }
        transaction { BackupEntity.findById(backupId)?.delete() }
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
        transaction {
            val e = ServerEntity.findById(serverId) ?: return@transaction
            e.backupSchedule = req.backupSchedule
            if (req.backupMaxCount != null) e.backupMaxCount = req.backupMaxCount
        }
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
