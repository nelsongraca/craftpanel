package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

data class EnvVarRow(val key: String, val value: String)

interface EnvVarsRepository {

    fun getEnvVars(serverId: Uuid): List<EnvVarRow>
    fun replaceEnvVars(serverId: Uuid, envVars: List<EnvVarRow>)
    fun deleteEnvVarsForServer(serverId: Uuid)
}
