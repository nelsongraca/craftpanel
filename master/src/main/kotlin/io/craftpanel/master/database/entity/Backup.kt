package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.Backups
import io.craftpanel.master.service.repo.BackupRow
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class Backup(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<Backup>(Backups)

    var serverId by Backups.serverId
    var nodeId by Backups.nodeId
    var trigger by Backups.trigger
    var status by Backups.status
    var filePath by Backups.filePath
    var sizeBytes by Backups.sizeBytes
    var errorMessage by Backups.errorMessage
    var createdAt by Backups.createdAt
    var completedAt by Backups.completedAt

    fun toBackupRow() = BackupRow(
        id = id.value,
        serverId = serverId.value,
        nodeId = nodeId.value,
        trigger = trigger,
        status = status,
        filePath = filePath,
        sizeBytes = sizeBytes,
        errorMessage = errorMessage,
        createdAt = createdAt.toUtcString(),
        completedAt = completedAt?.toUtcString()
    )
}
