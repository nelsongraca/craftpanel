package io.craftpanel.master.scheduler

import io.craftpanel.master.service.ConflictException
import io.craftpanel.master.service.ServerLifecycleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class ServerStopJobHandler(private val lifecycleService: ServerLifecycleService) : ScheduledJobHandler {

    private val log = LoggerFactory.getLogger(ServerStopJobHandler::class.java)
    override val jobType = "STOP"

    override suspend fun execute(context: JobExecutionContext) {
        log.info("Scheduled stop firing for server ${context.serverId}")
        runCatching {
            withContext(Dispatchers.IO) { lifecycleService.stopServer(context.serverId) }
        }.onFailure { e ->
            if (e is ConflictException) {
                log.info("Scheduled stop skipped for server ${context.serverId}: ${e.message}")
            } else {
                log.error("Scheduled stop failed for server ${context.serverId}", e)
            }
        }
    }
}
