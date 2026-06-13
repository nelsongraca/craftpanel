package io.craftpanel.master.service

import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.database.schema.Servers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

@Serializable
data class EnvVarItem(val key: String, val value: String)

@Serializable
data class EnvVarsResponse(@SerialName("env_vars") val envVars: List<EnvVarItem>)

@Serializable
data class PutEnvVarsRequest(@SerialName("env_vars") val envVars: List<EnvVarItem>)

@Serializable
data class PatchConfigModeRequest(@SerialName("config_mode") val configMode: String)

@Serializable
data class PatchStopCommandRequest(@SerialName("stop_command") val stopCommand: String)

class EnvVarsService {

    data class ServerScope(val serverId: Uuid, val networkId: Uuid?)

    fun getServerScope(serverId: Uuid): ServerScope? =
        transaction {
            Servers.selectAll()
                .where { Servers.id eq serverId }
                .firstOrNull()
                ?.let {
                    ServerScope(
                        serverId = serverId,
                        networkId = it[Servers.networkId],
                    )
                }
        }

    fun getEnvVars(serverId: Uuid): EnvVarsResponse {
        transaction {
            Servers.selectAll()
                .where { Servers.id eq serverId }
                .firstOrNull()
        } ?: throw NotFoundException("Server not found")

        val items = transaction {
            ServerEnvVars.selectAll()
                .where { ServerEnvVars.serverId eq serverId }
                .orderBy(ServerEnvVars.key)
                .map { EnvVarItem(it[ServerEnvVars.key], it[ServerEnvVars.value]) }
        }
        return EnvVarsResponse(items)
    }

    fun replaceEnvVars(serverId: Uuid, req: PutEnvVarsRequest): EnvVarsResponse {
        transaction {
            Servers.selectAll()
                .where { Servers.id eq serverId }
                .firstOrNull()
        } ?: throw NotFoundException("Server not found")

        val keys = req.envVars.map { it.key.trim() }
        if (keys.size != keys.toSet().size) throw UnprocessableException("Duplicate env var keys")

        transaction {
            ServerEnvVars.deleteWhere { ServerEnvVars.serverId eq serverId }
            for (item in req.envVars) {
                ServerEnvVars.insert {
                    it[ServerEnvVars.serverId] = serverId
                    it[ServerEnvVars.key] = item.key.trim()
                    it[ServerEnvVars.value] = item.value
                }
            }
            Servers.update({ Servers.id eq serverId }) { it[Servers.needsRecreate] = true }
        }
        return getEnvVars(serverId)
    }

    fun updateStopCommand(serverId: Uuid, req: PatchStopCommandRequest) {
        transaction {
            Servers.selectAll()
                .where { Servers.id eq serverId }
                .firstOrNull()
        } ?: throw NotFoundException("Server not found")

        transaction {
            Servers.update({ Servers.id eq serverId }) {
                it[Servers.stopCommand] = req.stopCommand
                it[Servers.needsRecreate] = true
            }
        }
    }

    fun updateConfigMode(serverId: Uuid, req: PatchConfigModeRequest): EnvVarsResponse {
        if (req.configMode !in setOf("MANAGED", "MANUAL"))
            throw BadRequestException("config_mode must be MANAGED or MANUAL")

        transaction {
            Servers.selectAll()
                .where { Servers.id eq serverId }
                .firstOrNull()
        } ?: throw NotFoundException("Server not found")

        transaction {
            Servers.update({ Servers.id eq serverId }) {
                it[Servers.configMode] = req.configMode
                it[Servers.needsRecreate] = true
            }
        }
        return getEnvVars(serverId)
    }
}
