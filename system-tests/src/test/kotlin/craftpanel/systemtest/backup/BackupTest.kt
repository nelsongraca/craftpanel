package craftpanel.systemtest.backup

import craftpanel.systemtest.client.model.*
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty

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

            should("list backups returns empty for new server") {
                val backups = api.listBackups(serverId)
                backups.getOrDefault("backups", emptyList())
                    .shouldBeEmpty()
            }

            should("get backup schedule returns defaults") {
                val schedule = api.getBackupSchedule(serverId)
                schedule.backupMaxCount shouldBe 10
            }

            should("updates backup schedule") {
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

            should("triggers backup on a running server") {
                val backup = api.triggerBackup(serverId)
                backup.id.shouldNotBeEmpty()
                backup.serverId shouldBe serverId
                backup.trigger shouldBe BackupTrigger.MANUAL

                // Wait for backup to complete (up to 30s)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)
            }

            should("triggers and then deletes a backup") {
                val backup = api.triggerBackup(serverId)
                helper.awaitBackupCompleted(serverId, backup.id)

                api.deleteBackup(serverId, backup.id)
            }
        }
    }
}
