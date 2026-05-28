package io.craftpanel.master.service

import com.craftpanel.agent.v1.MasterMessage
import com.craftpanel.agent.v1.deleteBackupCommand
import com.craftpanel.agent.v1.masterMessage
import com.craftpanel.agent.v1.triggerBackupCommand
import io.craftpanel.master.database.schema.Backups
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.grpc.DataServiceProxy
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

private val CRON_REGEX = Regex("""^(\*|[0-9,\-*/]+)\s+(\*|[0-9,\-*/]+)\s+(\*|[0-9,\-*/]+)\s+(\*|[0-9,\-*/]+)\s+(\*|[0-9,\-*/]+)$""")

@Serializable
data class BackupResponse(
    val id: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("node_id") val nodeId: String,
    val trigger: String,
    val status: String,
    @SerialName("file_path") val filePath: String?,
    @SerialName("size_bytes") val sizeBytes: Long?,
    @SerialName("error_message") val errorMessage: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") val completedAt: String?,
)

@Serializable
data class BackupScheduleResponse(
    @SerialName("backup_schedule") val backupSchedule: String?,
    @SerialName("backup_max_count") val backupMaxCount: Int,
)

@Serializable
data class PutBackupScheduleRequest(
    @SerialName("backup_schedule") val backupSchedule: String?,
    @SerialName("backup_max_count") val backupMaxCount: Int? = null,
)

data class BackupDownloadInfo(val serverId: String, val filePath: String)

class BackupService(
    private val sendToNode: (String, MasterMessage) -> Boolean,
    private val dataServiceProxy: DataServiceProxy,
) {

    data class ServerScope(val networkId: UUID?)

    fun getServerScope(serverId: kotlin.uuid.Uuid): ServerScope? =
        transaction {
            Servers.selectAll().where { Servers.id eq serverId }.firstOrNull()
                ?.let { ServerScope(it[Servers.networkId]?.let { nid -> UUID.fromString(nid.toString()) }) }
        }

    fun listBackups(serverId: kotlin.uuid.Uuid): List<BackupResponse> =
        transaction {
            Backups.selectAll()
                .where { Backups.serverId eq serverId }
                .orderBy(Backups.createdAt, SortOrder.DESC)
                .map { it.toBackupResponse() }
        }

    fun triggerBackup(serverId: kotlin.uuid.Uuid): BackupResponse {
        val serverRow = transaction { Servers.selectAll().where { Servers.id eq serverId }.firstOrNull() }
            ?: throw NotFoundException("Server not found")
        val nodeKotlinId = serverRow[Servers.nodeId]
        val nodeRow = transaction { Nodes.selectAll().where { Nodes.id eq nodeKotlinId }.firstOrNull() }
            ?: throw UnprocessableException("Node not found")
        val nodeId = nodeKotlinId.toString()
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        val backupResponse = transaction {
            val maxCount = serverRow[Servers.backupMaxCount]
            val completed = Backups.selectAll()
                .where { (Backups.serverId eq serverId) and (Backups.status eq "COMPLETED") }
                .orderBy(Backups.createdAt, SortOrder.ASC)
                .toList()
            if (completed.size >= maxCount) {
                val toDelete = completed.take(completed.size - maxCount + 1)
                for (old in toDelete) {
                    val filePath = old[Backups.filePath]
                    val backupNodeId = old[Backups.nodeId].toString()
                    if (!filePath.isNullOrEmpty()) {
                        sendToNode(backupNodeId, masterMessage {
                            deleteBackup = deleteBackupCommand {
                                backupId = old[Backups.id].toString()
                                this.filePath = filePath
                            }
                        })
                    }
                    Backups.deleteWhere { Backups.id eq old[Backups.id] }
                }
            }

            val destPath = "${nodeRow[Nodes.dataPath]}/backups/${UUID.randomUUID()}.tar.gz"
            val backupId = Backups.insert {
                it[Backups.serverId] = serverId
                it[Backups.nodeId] = nodeKotlinId
                it[Backups.trigger] = "MANUAL"
                it[Backups.status] = "IN_PROGRESS"
            }[Backups.id]

            val row = Backups.selectAll().where { Backups.id eq backupId }.first()

            val sent = sendToNode(nodeId, masterMessage {
                triggerBackup = triggerBackupCommand {
                    this.backupId = backupId.toString()
                    this.serverId = serverId.toString()
                    containerName = "craftpanel-$serverId"
                    destinationPath = destPath
                }
            })

            if (!sent) {
                Backups.update({ Backups.id eq backupId }) {
                    it[Backups.status] = "FAILED"
                    it[Backups.errorMessage] = "Agent not connected"
                    it[Backups.completedAt] = now
                }
            }

            row.toBackupResponse()
        }

        if (backupResponse.status == "FAILED" && backupResponse.errorMessage == "Agent not connected")
            throw BadGatewayException("Agent not connected")
        return backupResponse
    }

    fun deleteBackup(serverId: kotlin.uuid.Uuid, backupId: kotlin.uuid.Uuid) {
        val backup = transaction {
            Backups.selectAll()
                .where { (Backups.id eq backupId) and (Backups.serverId eq serverId) }
                .firstOrNull()
        } ?: throw NotFoundException("Backup not found")
        if (backup[Backups.status] == "IN_PROGRESS")
            throw ConflictException("Cannot delete a backup that is in progress")
        val filePath = backup[Backups.filePath]
        val backupNodeId = backup[Backups.nodeId].toString()
        val backupIdStr = backupId.toString()
        if (!filePath.isNullOrEmpty()) {
            sendToNode(backupNodeId, masterMessage {
                deleteBackup = deleteBackupCommand {
                    this.backupId = backupIdStr
                    this.filePath = filePath
                }
            })
        }
        transaction { Backups.deleteWhere { Backups.id eq backupId } }
    }

    fun resolveDownload(serverId: kotlin.uuid.Uuid, backupId: kotlin.uuid.Uuid): BackupDownloadInfo {
        val backup = transaction {
            Backups.selectAll()
                .where { (Backups.id eq backupId) and (Backups.serverId eq serverId) }
                .firstOrNull()
        } ?: throw NotFoundException("Backup not found")
        if (backup[Backups.status] != "COMPLETED")
            throw ConflictException("Backup is not in COMPLETED status")
        val filePath = backup[Backups.filePath] ?: throw NotFoundException("Backup file path not available")
        return BackupDownloadInfo(serverId = serverId.toString(), filePath = filePath)
    }

    fun downloadStream(info: BackupDownloadInfo) = dataServiceProxy.downloadFile(info.serverId, info.filePath)

    fun getSchedule(serverId: kotlin.uuid.Uuid): BackupScheduleResponse {
        val serverRow = transaction { Servers.selectAll().where { Servers.id eq serverId }.firstOrNull() }
            ?: throw NotFoundException("Server not found")
        return BackupScheduleResponse(
            backupSchedule = serverRow[Servers.backupSchedule],
            backupMaxCount = serverRow[Servers.backupMaxCount],
        )
    }

    fun updateSchedule(serverId: kotlin.uuid.Uuid, req: PutBackupScheduleRequest) {
        if (req.backupSchedule != null && !CRON_REGEX.matches(req.backupSchedule))
            throw UnprocessableException("Invalid cron expression")
        if (req.backupMaxCount != null && req.backupMaxCount < 1)
            throw UnprocessableException("backup_max_count must be at least 1")
        transaction {
            Servers.update({ Servers.id eq serverId }) {
                it[Servers.backupSchedule] = req.backupSchedule
                if (req.backupMaxCount != null) it[Servers.backupMaxCount] = req.backupMaxCount
                it[Servers.updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            }
        }
    }
}

private fun org.jetbrains.exposed.v1.core.ResultRow.toBackupResponse() = BackupResponse(
    id = this[Backups.id].toString(),
    serverId = this[Backups.serverId].toString(),
    nodeId = this[Backups.nodeId].toString(),
    trigger = this[Backups.trigger],
    status = this[Backups.status],
    filePath = this[Backups.filePath],
    sizeBytes = this[Backups.sizeBytes],
    errorMessage = this[Backups.errorMessage],
    createdAt = this[Backups.createdAt].toString(),
    completedAt = this[Backups.completedAt]?.toString(),
)
