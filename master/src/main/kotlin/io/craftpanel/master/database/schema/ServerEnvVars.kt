package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table

object ServerEnvVars : Table("server_env_vars") {

    val id = uuid("id").autoGenerate()
    val serverId = uuid("server_id").references(Servers.id)
    val key = varchar("key", 100)
    val value = text("value")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(serverId, key)
    }
}
