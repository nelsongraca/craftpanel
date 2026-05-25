package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime

object ServerMods : Table("server_mods") {
    val id = uuid("id").autoGenerate()
    val serverId = uuid("server_id").references(Servers.id)
    val modrinthProjectId = varchar("modrinth_project_id", 100)
    val modrinthVersionId = varchar("modrinth_version_id", 100)
    val filename = varchar("filename", 255)
    val sha512 = varchar("sha512", 128)
    val enabled = bool("enabled").default(true)
    val installedAt = datetime("installed_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}
