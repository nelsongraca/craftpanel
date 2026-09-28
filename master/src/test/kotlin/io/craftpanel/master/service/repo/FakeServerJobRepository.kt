package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakeServerJobRepository(private val state: FakeRepositories) : ServerJobRepository {

    override fun listEnabledServerJobs(): List<ServerJobRow> = state.serverJobs.values.filter { it.enabled }
        .map { it.toRow() }

    override fun listByServer(serverId: Uuid): List<ServerJobRow> = state.serverJobs.values.filter { it.serverId == serverId }
        .map { it.toRow() }

    override fun findById(id: Uuid): ServerJobRow? = state.serverJobs[id]?.toRow()

    override fun create(serverId: Uuid, type: String, cronExpression: String, payload: String?, enabled: Boolean): ServerJobRow {
        val job = FakeServerRepository.MutableServerJob(
            id = Uuid.random(),
            serverId = serverId,
            type = type,
            cronExpression = cronExpression,
            payload = payload,
            enabled = enabled
        )
        state.serverJobs[job.id] = job
        return job.toRow()
    }

    override fun update(id: Uuid, cronExpression: String?, payload: String?, enabled: Boolean?) {
        val job = state.serverJobs[id] ?: return
        if (cronExpression != null) job.cronExpression = cronExpression
        if (payload != null) job.payload = payload
        if (enabled != null) job.enabled = enabled
    }

    override fun delete(id: Uuid): Boolean = state.serverJobs.remove(id) != null

    fun addServerJob(job: FakeServerRepository.MutableServerJob) {
        state.serverJobs[job.id] = job
    }

    private fun FakeServerRepository.MutableServerJob.toRow() = ServerJobRow(
        id = id,
        serverId = serverId,
        type = type,
        cronExpression = cronExpression,
        payload = payload,
        enabled = enabled,
        lastFiredAt = lastFiredAt
    )
}
