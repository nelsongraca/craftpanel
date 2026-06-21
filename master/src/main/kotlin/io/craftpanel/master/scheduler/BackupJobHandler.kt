package io.craftpanel.master.scheduler

import io.craftpanel.master.domain.BackupTrigger
import io.craftpanel.master.service.BackupService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

class BackupJobHandler(private val backupService: BackupService) : ScheduledJobHandler {

    private val log = LoggerFactory.getLogger(BackupJobHandler::class.java)
    override val jobType = "BACKUP"

    override suspend fun execute(context: JobExecutionContext) {
        log.info("Scheduled backup firing for server ${context.serverId}")
        runCatching {
            withContext(Dispatchers.IO) {
                backupService.triggerBackup(context.serverId, trigger = BackupTrigger.SCHEDULED)
            }
        }.onFailure {
            log.error("Scheduled backup failed for server ${context.serverId}", it)
        }
    }
}
