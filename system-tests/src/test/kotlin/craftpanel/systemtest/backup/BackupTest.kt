package craftpanel.systemtest.backup

import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import craftpanel.systemtest.client.model.PutBackupScheduleRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldNotBeEmpty
import org.openapitools.client.infrastructure.ClientException

class BackupTest : BaseSystemTest() {

    init {
        context("Backup lifecycle") {

            should("list backups returns empty for new server") {
                val serverId = ServerHelper(api).createTestServer(nodeId)
                try {
                    val backups = api.listBackups(serverId)
                    backups.getOrDefault("backups", emptyList()).shouldBeEmpty()
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }

            should("get backup schedule returns defaults") {
                val serverId = ServerHelper(api).createTestServer(nodeId)
                try {
                    val schedule = api.getBackupSchedule(serverId)
                    schedule.backupMaxCount shouldBe 10
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }

            should("updates backup schedule") {
                val serverId = ServerHelper(api).createTestServer(nodeId)
                try {
                    val updated = api.updateBackupSchedule(
                        serverId,
                        PutBackupScheduleRequest(
                            backupSchedule = "0 */6 * * *",
                            backupMaxCount = 10
                        )
                    )
                    updated.backupSchedule shouldBe "0 */6 * * *"
                    updated.backupMaxCount shouldBe 10
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }

            should("triggers backup on a running server") {
                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(nodeId)
                try {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")

                    val backup = api.triggerBackup(serverId)
                    backup.id.shouldNotBeEmpty()
                    backup.serverId shouldBe serverId
                    backup.trigger shouldBe "MANUAL"

                    // Wait for backup to complete (up to 30s)
                    helper.awaitStatus(serverId, "HEALTHY")
                } finally {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }
            }

            should("triggers and then deletes a backup") {
                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(nodeId)
                try {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")

                    val backup = api.triggerBackup(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")

                    api.deleteBackup(serverId, backup.id)
                } finally {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }
            }
        }
    }
}
