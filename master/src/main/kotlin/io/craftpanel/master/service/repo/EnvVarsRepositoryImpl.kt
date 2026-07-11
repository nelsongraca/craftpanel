package io.craftpanel.master.service.repo

import io.craftpanel.master.database.schema.ServerEnvVars
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class EnvVarsRepositoryImpl : EnvVarsRepository {

    override fun getEnvVars(serverId: Uuid): List<EnvVarRow> = transaction {
        ServerEnvVars.selectAll()
            .where { ServerEnvVars.serverId eq serverId }
            .map { EnvVarRow(key = it[ServerEnvVars.key], value = it[ServerEnvVars.value]) }
    }

    override fun replaceEnvVars(serverId: Uuid, envVars: List<EnvVarRow>) {
        transaction {
            ServerEnvVars.deleteWhere { ServerEnvVars.serverId eq serverId }
            envVars.forEach { ev ->
                ServerEnvVars.insert {
                    it[ServerEnvVars.serverId] = serverId
                    it[ServerEnvVars.key] = ev.key
                    it[ServerEnvVars.value] = ev.value
                }
            }
        }
    }

    override fun deleteEnvVarsForServer(serverId: Uuid) {
        transaction { ServerEnvVars.deleteWhere { ServerEnvVars.serverId eq serverId } }
    }
}
