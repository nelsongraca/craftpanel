package io.craftpanel.master.service.repo

import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

data class ServerJobRow(val id: Uuid, val serverId: Uuid, val type: String, val cronExpression: String, val lastFiredAt: String?)

interface ServerJobRepository {

    fun listEnabledServerJobs(): List<ServerJobRow>
    fun updateServerJobLastFired(jobId: Uuid, lastFired: Instant)
}
