package io.craftpanel.master.scheduler

import io.craftpanel.master.domain.BackupTrigger
import io.craftpanel.master.service.BackupService
import io.kotest.core.spec.style.FunSpec
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class BackupJobHandlerTest :
    FunSpec({

        test("execute calls triggerBackup with SCHEDULED trigger") {
            val backupService = mockk<BackupService>()
            every { backupService.triggerBackup(any(), trigger = BackupTrigger.SCHEDULED) } returns mockk()

            val handler = BackupJobHandler(backupService)
            val serverId = Uuid.random()

            runTest {
                handler.execute(JobExecutionContext(serverId, jobId = null, scheduledAt = Clock.System.now()))
            }

            verify(exactly = 1) { backupService.triggerBackup(serverId, trigger = BackupTrigger.SCHEDULED) }
        }

        test("execute swallows exceptions from triggerBackup") {
            val backupService = mockk<BackupService>()
            every { backupService.triggerBackup(any(), any()) } throws RuntimeException("disk full")

            val handler = BackupJobHandler(backupService)

            runTest {
                // should not throw
                handler.execute(JobExecutionContext(Uuid.random(), jobId = null, scheduledAt = Clock.System.now()))
            }
        }
    })
