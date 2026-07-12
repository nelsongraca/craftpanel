package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

class FakeServerJobRepository(private val state: FakeRepositories) : ServerJobRepository {

    override fun listEnabledServerJobs(): List<ServerJobRow> = state.serverJobs.values.filter { it.enabled }
        .map { it.toRow() }

    override fun updateServerJobLastFired(jobId: Uuid, lastFired: kotlin.time.Instant) {
        state.serverJobs[jobId]?.lastFiredAt = lastFired.toString()
    }

    fun addServerJob(job: FakeServerRepository.MutableServerJob) {
        state.serverJobs[job.id] = job
    }

    private fun FakeServerRepository.MutableServerJob.toRow() = ServerJobRow(id, serverId, type, cronExpression, lastFiredAt)
}
