package craftpanel.systemtest.jobs

import craftpanel.systemtest.client.model.*
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.pollUntilNotNull
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

@Tags("ScheduledJobs")
class ScheduledJobsTest : BaseSystemTest() {

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

        context("Scheduled jobs") {

            should("start with no jobs") {
                api.listScheduledJobs(serverId).shouldBeEmpty()
            }

            should("create, update and delete a job") {
                val created = api.createScheduledJob(
                    serverId,
                    CreateScheduledJobRequest(type = ScheduledJobType.RESTART, cronExpression = "0 4 * * *")
                )
                created.type shouldBe ScheduledJobType.RESTART
                created.enabled shouldBe true

                val updated = api.updateScheduledJob(serverId, created.id, UpdateScheduledJobRequest(enabled = false))
                updated.enabled shouldBe false

                api.deleteScheduledJob(serverId, created.id)
                api.listScheduledJobs(serverId).shouldBeEmpty()
            }

            should("list the schedulable job types") {
                val types = api.listJobTypes().types
                types shouldBe listOf(
                    ScheduledJobType.START,
                    ScheduledJobType.STOP,
                    ScheduledJobType.RESTART,
                    ScheduledJobType.RCON_COMMAND
                )
            }

            should("execute a scheduled RCON command against a running server") {
                val command = "say hello from cron"
                val job = api.createScheduledJob(
                    serverId,
                    CreateScheduledJobRequest(
                        type = ScheduledJobType.RCON_COMMAND,
                        cronExpression = "* * * * *",
                        payload = command
                    )
                )

                val container = containerName(serverId)
                val observed = pollUntilNotNull(timeoutMs = 150_000) {
                    runCatching { execInContainer(container, "cat", "/tmp/rcon-commands.log") }
                        .getOrNull()
                        ?.takeIf { command in it }
                }

                api.deleteScheduledJob(serverId, job.id)

                checkNotNull(observed) { "Scheduled RCON command was not observed in the container within 150s" }
            }
        }
    }
}
