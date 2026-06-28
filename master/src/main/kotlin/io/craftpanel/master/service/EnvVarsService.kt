package io.craftpanel.master.service

import io.craftpanel.master.domain.ConfigMode
import io.craftpanel.master.service.repo.EnvVarRow
import io.craftpanel.master.service.repo.ServerRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

class EnvVarsService(private val serverRepository: ServerRepository) {

    fun getEnvVars(serverId: Uuid): EnvVarsResponse {
        serverRepository.findById(serverId) ?: throw NotFoundException("Server not found")
        val items = serverRepository.getEnvVars(serverId)
            .map { EnvVarItem(it.key, it.value) }
        return EnvVarsResponse(items)
    }

    fun replaceEnvVars(serverId: Uuid, req: PutEnvVarsRequest): EnvVarsResponse {
        serverRepository.findById(serverId) ?: throw NotFoundException("Server not found")
        val keys = req.envVars.map { it.key.trim() }
        if (keys.size != keys.toSet().size) throw UnprocessableException("Duplicate env var keys")
        serverRepository.replaceEnvVars(serverId, req.envVars.map { EnvVarRow(it.key.trim(), it.value) })
        serverRepository.updateNeedsRecreate(serverId, true)
        return getEnvVars(serverId)
    }

    fun updateStopCommand(serverId: Uuid, req: PatchStopCommandRequest) {
        serverRepository.findById(serverId) ?: throw NotFoundException("Server not found")
        serverRepository.updateStopCommand(serverId, req.stopCommand)
        serverRepository.updateNeedsRecreate(serverId, true)
    }

    fun updateConfigMode(serverId: Uuid, req: PatchConfigModeRequest): EnvVarsResponse {
        serverRepository.findById(serverId) ?: throw NotFoundException("Server not found")
        serverRepository.updateConfigMode(serverId, req.configMode.name)
        serverRepository.updateNeedsRecreate(serverId, true)
        return getEnvVars(serverId)
    }
}
