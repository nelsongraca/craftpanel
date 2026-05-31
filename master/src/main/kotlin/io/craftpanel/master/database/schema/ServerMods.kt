package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object ServerMods : Table("server_mods") {

    val id = uuid("id").autoGenerate()
    val serverId = uuid("server_id").references(Servers.id, onDelete = ReferenceOption.CASCADE)
    val modrinthProjectId = varchar("modrinth_project_id", 64)
    val displayName = varchar("display_name", 128)
    val pinStrategy = varchar("pin_strategy", 10)   // PINNED|LATEST|BETA|ALPHA
    val pinnedVersionId = varchar("pinned_version_id", 64).nullable()
    val installedVersionId = varchar("installed_version_id", 64).nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
