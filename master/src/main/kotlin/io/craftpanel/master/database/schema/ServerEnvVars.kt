package io.craftpanel.master.database.schema

import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.ReferenceOption

object ServerEnvVars : UuidTable("server_env_vars") {

    val serverId = reference("server_id", Servers, onDelete = ReferenceOption.CASCADE)
    val key = varchar("key", 128)
    val value = text("value")

    init {
        uniqueIndex(serverId, key)
    }
}
