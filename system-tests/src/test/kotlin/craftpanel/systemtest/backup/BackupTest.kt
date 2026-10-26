package craftpanel.systemtest.backup

import craftpanel.systemtest.client.model.*
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.SharedStack
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import kotlinx.coroutines.delay
import java.io.File

@Tags("BackupAlerts")
class BackupTest : BaseSystemTest() {

    init {

        lateinit var serverId: String

        beforeSpec {
            serverId = helper.createTestServer(nodeId)
            api.startServer(serverId)
            helper.awaitStatus(serverId, ServerStatus.HEALTHY)
        }
        afterSpec {
            runCatching { api.stopServer(serverId) }
            helper.awaitStoppedOrGone(serverId)
            runCatching { api.deleteServer(serverId) }
        }

        context("Backup lifecycle") {

            should("return an empty backup list for a new server") {
                val backups = api.listBackups(serverId)
                backups.getOrDefault("backups", emptyList())
                    .shouldBeEmpty()
            }

            should("return the default backup schedule") {
                val schedule = api.getBackupSchedule(serverId)
                schedule.backupMaxCount shouldBe 10
            }

            should("update the backup schedule") {
                val updated = api.updateBackupSchedule(
                    serverId,
                    PutBackupScheduleRequest(
                        backupSchedule = "0 */6 * * *",
                        backupMaxCount = 10
                    )
                )
                updated.backupSchedule shouldBe "0 */6 * * *"
                updated.backupMaxCount shouldBe 10
            }

            should("trigger a backup on a running server") {
                val backup = api.triggerBackup(serverId)
                backup.id.shouldNotBeEmpty()
                backup.serverId shouldBe serverId
                backup.trigger shouldBe BackupTrigger.MANUAL

                // Wait for backup to complete (up to 30s)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)
            }

            should("trigger and then delete a backup") {
                val backup = api.triggerBackup(serverId)
                helper.awaitBackupCompleted(serverId, backup.id)

                val completed = api.listBackups(serverId)
                    .getOrDefault("backups", emptyList())
                    .single { it.id == backup.id }
                completed.status shouldBe BackupStatus.COMPLETED
                // The archive path must survive completion, otherwise master can never purge it.
                completed.filePath.shouldNotBeNull()

                val archive = File(SharedStack.agentDataDirs().first(), "backups/${backup.id}.tar.gz")
                archive.exists() shouldBe true

                api.deleteBackup(serverId, backup.id)

                val deadline = System.currentTimeMillis() + 30_000
                while (archive.exists() && System.currentTimeMillis() < deadline) delay(200)
                archive.exists() shouldBe false
            }
        }
    }
}
