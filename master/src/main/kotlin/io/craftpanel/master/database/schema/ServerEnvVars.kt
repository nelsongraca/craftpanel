package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.ReferenceOption

object ServerEnvVars : Table("server_env_vars") {

    val id = uuid("id").autoGenerate()
    val serverId = reference("server_id", Servers, onDelete = ReferenceOption.CASCADE)
    val key = varchar("key", 128)
    val value = text("value")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(serverId, key)
    }
}
