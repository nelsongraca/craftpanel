package io.craftpanel.master.scheduler

import io.craftpanel.master.service.ConflictException
import io.craftpanel.master.service.ServerLifecycleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class ServerRestartJobHandler(private val lifecycleService: ServerLifecycleService) : ScheduledJobHandler {

    private val log = LoggerFactory.getLogger(ServerRestartJobHandler::class.java)
    override val jobType = "RESTART"

    override suspend fun execute(context: JobExecutionContext) {
        log.info("Scheduled restart firing for server ${context.serverId}")
        runCatching {
            withContext(Dispatchers.IO) { lifecycleService.restartServer(context.serverId) }
        }.onFailure { e ->
            if (e is ConflictException) {
                log.info("Scheduled restart skipped for server ${context.serverId}: ${e.message}")
            } else {
                log.error("Scheduled restart failed for server ${context.serverId}", e)
            }
        }
    }
}
