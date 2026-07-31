package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.ServerMods
import io.craftpanel.master.service.repo.ModRow
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class Mod(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<Mod>(ServerMods)

    var serverId by ServerMods.serverId
    var modrinthProjectId by ServerMods.modrinthProjectId
    var displayName by ServerMods.displayName
    var pinStrategy by ServerMods.pinStrategy
    var pinnedVersionId by ServerMods.pinnedVersionId
    var installedVersionId by ServerMods.installedVersionId
    var createdAt by ServerMods.createdAt
    var updatedAt by ServerMods.updatedAt

    fun toModRow() = ModRow(
        id = id.value,
        serverId = serverId.value,
        modrinthProjectId = modrinthProjectId,
        displayName = displayName,
        pinStrategy = pinStrategy,
        pinnedVersionId = pinnedVersionId,
        installedVersionId = installedVersionId,
        createdAt = createdAt.toUtcString(),
        updatedAt = updatedAt.toUtcString()
    )
}
