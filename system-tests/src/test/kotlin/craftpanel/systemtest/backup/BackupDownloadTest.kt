package craftpanel.systemtest.backup

import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import craftpanel.systemtest.harness.pollUntilNotNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.openapitools.client.infrastructure.ClientException

class BackupDownloadTest : BaseSystemTest() {

    init {
        describe("Backup download") {

            it("downloads a completed backup") {
                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(nodeId)
                try {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")

                    val backup = api.triggerBackup(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")

                    val completed = pollUntilNotNull(30_000) {
                        val backups = api.listBackups(serverId)
                        backups["backups"]
                            ?.firstOrNull { it.id == backup.id && it.status == "COMPLETED" }
                    }
                    completed shouldNotBe null
                    completed!!.sizeBytes shouldNotBe null

                    val bytes = api.downloadBackup(serverId, backup.id)
                    bytes shouldNotBe null
                    bytes.size.toLong() shouldBe completed.sizeBytes
                } finally {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }
            }

            it("downloading a non-existent backup returns 404") {
                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(nodeId)
                try {
                    val ex = shouldThrow<ClientException> {
                        api.downloadBackup(
                            serverId,
                            "00000000-0000-0000-0000-000000000000"
                        )
                    }
                    ex.statusCode shouldBe 404
                } finally {
                    runCatching { api.deleteServer(serverId) }
                }
            }

            it("downloading an in-progress backup returns 409") {
                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(nodeId)
                try {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")

                    val backup = api.triggerBackup(serverId)
                    val ex = shouldThrow<ClientException> {
                        api.downloadBackup(serverId, backup.id)
                    }
                    ex.statusCode shouldBe 409
                } finally {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }
            }
        }
    }
}
