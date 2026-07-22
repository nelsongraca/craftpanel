package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakeEnvVarsRepository(private val state: FakeRepositories) : EnvVarsRepository {

    override fun getEnvVars(serverId: Uuid): List<EnvVarRow> = state.envVars[serverId]?.toList() ?: emptyList()
    override fun replaceEnvVars(serverId: Uuid, envVars: List<EnvVarRow>) {
        state.envVars[serverId] = envVars.toMutableList()
    }

    override fun upsertEnvVar(serverId: Uuid, key: String, value: String) {
        val vars = state.envVars.getOrPut(serverId) { mutableListOf() }
        val idx = vars.indexOfFirst { it.key == key }
        if (idx >= 0) {
            vars[idx] = EnvVarRow(key, value)
        } else {
            vars.add(EnvVarRow(key, value))
        }
    }

    override fun deleteEnvVarsForServer(serverId: Uuid) {
        state.envVars.remove(serverId)
    }
}
