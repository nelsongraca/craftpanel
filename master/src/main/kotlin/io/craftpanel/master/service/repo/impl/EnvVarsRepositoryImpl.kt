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

    override fun upsertEnvVar(serverId: Uuid, key: String, value: String) {
        transaction {
            val existing = ServerEnvVars.selectAll()
                .where { (ServerEnvVars.serverId eq serverId) and (ServerEnvVars.key eq key) }
                .singleOrNull()
            if (existing != null) {
                ServerEnvVars.update({ (ServerEnvVars.serverId eq serverId) and (ServerEnvVars.key eq key) }) {
                    it[ServerEnvVars.value] = value
                }
            } else {
                ServerEnvVars.insert {
                    it[ServerEnvVars.serverId] = serverId
                    it[ServerEnvVars.key] = key
                    it[ServerEnvVars.value] = value
                }
            }
        }
    }

    override fun deleteEnvVarsForServer(serverId: Uuid) {
        transaction { ServerEnvVars.deleteWhere { ServerEnvVars.serverId eq serverId } }
    }
}
