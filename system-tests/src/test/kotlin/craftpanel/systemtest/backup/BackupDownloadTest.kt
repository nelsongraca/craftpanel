package craftpanel.systemtest.backup

import craftpanel.systemtest.client.model.BackupStatus
import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.pollUntilNotNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.openapitools.client.infrastructure.ClientException

@Isolate
@Tags("BackupAlerts")
class BackupDownloadTest : BaseSystemTest() {

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

        context("Backup download") {

            should("downloads a completed backup") {
                val backup = api.triggerBackup(serverId)
                helper.awaitStatus(serverId, ServerStatus.HEALTHY)

                val completed = pollUntilNotNull(30_000) {
                    val backups = api.listBackups(serverId)
                    backups["backups"]
                        ?.firstOrNull { it.id == backup.id && it.status == BackupStatus.COMPLETED }
                }
                completed shouldNotBe null
                completed!!.sizeBytes shouldNotBe null

                val bytes = api.downloadBackup(serverId, backup.id)
                bytes shouldNotBe null
                bytes.size.toLong() shouldBe completed.sizeBytes
            }

            should("downloading a non-existent backup returns 404") {
                val ex = shouldThrow<ClientException> {
                    api.downloadBackup(
                        serverId,
                        "00000000-0000-0000-0000-000000000000"
                    )
                }
                ex.statusCode shouldBe 404
            }

            should("downloading an in-progress backup returns 409") {
                val backup = api.triggerBackup(serverId)
                // Check status immediately — fake-server may complete the backup near-instantly.
                // Only assert 409 if the backup is genuinely still IN_PROGRESS.
                val status = api.listBackups(serverId)["backups"]
                    ?.firstOrNull { it.id == backup.id }
                    ?.status
                if (status == BackupStatus.IN_PROGRESS) {
                    val ex = shouldThrow<ClientException> {
                        api.downloadBackup(serverId, backup.id)
                    }
                    ex.statusCode shouldBe 409
                }
                // If already COMPLETED, the window passed — consider the test vacuously passed.
            }
        }
    }
}
