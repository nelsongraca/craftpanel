package io.craftpanel.master.service.repo

import io.craftpanel.master.database.schema.ServerMods
import io.craftpanel.master.util.toUtcString
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ModRepositoryImpl : ModRepository {

    override fun listMods(serverId: Uuid): List<ModRow> = transaction {
        ServerMods.selectAll()
            .where { ServerMods.serverId eq serverId }
            .map { it.toModRow() }
    }

    override fun findModById(id: Uuid): ModRow? = transaction {
        ServerMods.selectAll()
            .where { ServerMods.id eq id }
            .firstOrNull()
            ?.toModRow()
    }

    override fun findModByProjectId(serverId: Uuid, projectId: String): ModRow? = transaction {
        ServerMods.selectAll()
            .where { (ServerMods.serverId eq serverId) and (ServerMods.modrinthProjectId eq projectId) }
            .firstOrNull()
            ?.toModRow()
    }

    override fun createMod(serverId: Uuid, modrinthProjectId: String, displayName: String, pinStrategy: String, pinnedVersionId: String?, installedVersionId: String?): ModRow = transaction {
        val id = ServerMods.insert {
            it[ServerMods.serverId] = serverId
            it[ServerMods.modrinthProjectId] = modrinthProjectId
            it[ServerMods.displayName] = displayName
            it[ServerMods.pinStrategy] = pinStrategy
            it[ServerMods.pinnedVersionId] = pinnedVersionId
            it[ServerMods.installedVersionId] = installedVersionId
        }[ServerMods.id]
        ServerMods.selectAll()
            .where { ServerMods.id eq id }
            .first()
            .toModRow()
    }

    override fun updateMod(id: Uuid, pinStrategy: String?, pinnedVersionId: String?, installedVersionId: String?) {
        transaction {
            ServerMods.update({ ServerMods.id eq id }) {
                if (pinStrategy != null) it[ServerMods.pinStrategy] = pinStrategy
                it[ServerMods.pinnedVersionId] = pinnedVersionId?.ifEmpty { null }
                if (installedVersionId != null) it[ServerMods.installedVersionId] = installedVersionId
            }
        }
    }

    override fun deleteMod(id: Uuid) {
        transaction { ServerMods.deleteWhere { ServerMods.id eq id } }
    }

    override fun deleteModsForServer(serverId: Uuid) {
        transaction { ServerMods.deleteWhere { ServerMods.serverId eq serverId } }
    }
}

private fun ResultRow.toModRow() = ModRow(
    id = this[ServerMods.id],
    serverId = this[ServerMods.serverId],
    modrinthProjectId = this[ServerMods.modrinthProjectId],
    displayName = this[ServerMods.displayName],
    pinStrategy = this[ServerMods.pinStrategy],
    pinnedVersionId = this[ServerMods.pinnedVersionId],
    installedVersionId = this[ServerMods.installedVersionId],
    createdAt = this[ServerMods.createdAt].toUtcString(),
    updatedAt = this[ServerMods.updatedAt].toUtcString()
)
