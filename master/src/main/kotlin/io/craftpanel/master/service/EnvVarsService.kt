package io.craftpanel.master.service

import io.craftpanel.master.database.entity.EnvVar
import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.ConfigMode
import io.craftpanel.master.service.repo.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

@Serializable
data class EnvVarItem(val key: String, val value: String)

@Serializable
data class EnvVarsResponse(@SerialName("env_vars") val envVars: List<EnvVarItem>)

@Serializable
data class PutEnvVarsRequest(@SerialName("env_vars") val envVars: List<EnvVarItem>)

@Serializable
data class PatchConfigModeRequest(@SerialName("config_mode") val configMode: ConfigMode)

@Serializable
data class PatchStopCommandRequest(@SerialName("stop_command") val stopCommand: String)

class EnvVarsService(private val serverRepository: ServerRepository, private val envVarsRepository: EnvVarsRepository) {

    fun getEnvVars(serverId: Uuid): EnvVarsResponse {
        serverRepository.findById(serverId) ?: throw NotFoundException("Server not found")
        val items = envVarsRepository.getEnvVars(serverId)
            .map { EnvVarItem(it.key, it.value) }
        return EnvVarsResponse(items)
    }

    fun replaceEnvVars(serverId: Uuid, req: PutEnvVarsRequest): EnvVarsResponse {
        serverRepository.findById(serverId) ?: throw NotFoundException("Server not found")
        val keys = req.envVars.map { it.key.trim() }
        if (keys.size != keys.toSet().size) throw UnprocessableException("Duplicate env var keys")
        transaction {
            EnvVar.find { ServerEnvVars.serverId eq serverId }.forEach { it.delete() }
            req.envVars.forEach { ev ->
                EnvVar.new {
                    this.serverId = EntityID(serverId, Servers)
                    key = ev.key.trim()
                    value = ev.value
                }
            }
            Server.findById(serverId)?.let { it.needsRecreate = true }
        }
        return getEnvVars(serverId)
    }

    fun updateStopCommand(serverId: Uuid, req: PatchStopCommandRequest) {
        serverRepository.findById(serverId) ?: throw NotFoundException("Server not found")
        transaction {
            val e = Server.findById(serverId) ?: return@transaction
            e.stopCommand = req.stopCommand
            e.needsRecreate = true
        }
    }

    fun updateConfigMode(serverId: Uuid, req: PatchConfigModeRequest): EnvVarsResponse {
        serverRepository.findById(serverId) ?: throw NotFoundException("Server not found")
        transaction {
            val e = Server.findById(serverId) ?: return@transaction
            e.configMode = req.configMode.name
            e.needsRecreate = true
        }
        return getEnvVars(serverId)
    }
}