package io.craftpanel.master.scheduler

interface ScheduledJobHandler {

    val jobType: String

    suspend fun execute(context: JobExecutionContext)
}

data class JobExecutionContext(
    val serverId: kotlin.uuid.Uuid,
    val jobId: kotlin.uuid.Uuid?,
    val scheduledAt: kotlin.time.Instant,
    /** Job-type-specific argument (e.g. the RCON command for `RCON_COMMAND`); null otherwise. */
    val payload: String? = null
)
