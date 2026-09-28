package io.craftpanel.master.scheduler

import io.craftpanel.master.service.ConflictException
import io.craftpanel.master.service.ServerLifecycleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class ServerStartJobHandler(private val lifecycleService: ServerLifecycleService) : ScheduledJobHandler {

    private val log = LoggerFactory.getLogger(ServerStartJobHandler::class.java)
    override val jobType = "START"

    override suspend fun execute(context: JobExecutionContext) {
        log.info("Scheduled start firing for server ${context.serverId}")
        runCatching {
            withContext(Dispatchers.IO) { lifecycleService.startServer(context.serverId) }
        }.onFailure { e ->
            if (e is ConflictException) {
                log.info("Scheduled start skipped for server ${context.serverId}: ${e.message}")
            } else {
                log.error("Scheduled start failed for server ${context.serverId}", e)
            }
        }
    }
}
