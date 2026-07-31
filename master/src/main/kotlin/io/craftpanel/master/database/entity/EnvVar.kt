package io.craftpanel.master.database.entity

import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.service.repo.EnvVarRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.UuidEntity
import org.jetbrains.exposed.v1.dao.UuidEntityClass
import kotlin.uuid.Uuid

class EnvVar(id: EntityID<Uuid>) : UuidEntity(id) {
    companion object : UuidEntityClass<EnvVar>(ServerEnvVars)

    var serverId by ServerEnvVars.serverId
    var key by ServerEnvVars.key
    var value by ServerEnvVars.value

    fun toEnvVarRow() = EnvVarRow(key = key, value = value)
}
