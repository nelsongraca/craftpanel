package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakeEnvVarsRepository(private val state: FakeRepositories) : EnvVarsRepository {

    override fun getEnvVars(serverId: Uuid): List<EnvVarRow> = state.envVars[serverId]?.toList() ?: emptyList()
}