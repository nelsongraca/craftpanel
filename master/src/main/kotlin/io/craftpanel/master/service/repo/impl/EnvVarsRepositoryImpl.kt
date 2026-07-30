package io.craftpanel.master.service.repo.impl

import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class EnvVarsRepositoryImpl : EnvVarsRepository {

    override fun getEnvVars(serverId: Uuid): List<EnvVarRow> = transaction {
        ServerEnvVars.selectAll()
            .where { ServerEnvVars.serverId eq serverId }
            .map { EnvVarRow(key = it[ServerEnvVars.key], value = it[ServerEnvVars.value]) }
    }
}