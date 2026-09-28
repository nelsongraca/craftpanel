package io.craftpanel.master.service

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.ScheduledJobType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ScheduledJobServiceTest :
    FunSpec({

        val repos = TestRepositories()
        val service = ScheduledJobService(repos.serverRepository, repos.serverJobRepository)

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        fun createNode(): Uuid = transaction {
            Nodes.insert {
                it[Nodes.hostname] = "node-${Uuid.random()}"
                it[Nodes.displayName] = "Test"
                it[Nodes.publicIp] = "1.2.3.4"
                it[Nodes.privateIp] = "10.0.0.1"
                it[Nodes.tokenHash] = Uuid.random().toString().replace("-", "").padEnd(64, '0')
                it[Nodes.status] = "ACTIVE"
                it[Nodes.health] = "HEALTHY"
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        fun createServer(nodeId: Uuid): Uuid = transaction {
            Servers.insert {
                it[Servers.nodeId] = nodeId
                it[Servers.name] = "srv-${Uuid.random()}"
                it[Servers.hostPort] = 25565
                it[Servers.memoryMb] = 1024
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }

        test("create stores a valid cron job") {
            val serverId = createServer(createNode())

            val created = service.create(serverId, CreateScheduledJobRequest(ScheduledJobType.RESTART, "0 3 * * *"))

            created.type shouldBe ScheduledJobType.RESTART
            created.cronExpression shouldBe "0 3 * * *"
            created.payload shouldBe null
            created.enabled shouldBe true
            service.list(serverId).map { it.id } shouldContainExactly listOf(created.id)
        }

        test("create rejects an invalid cron expression") {
            val serverId = createServer(createNode())

            shouldThrow<UnprocessableException> {
                service.create(serverId, CreateScheduledJobRequest(ScheduledJobType.START, "not-a-cron"))
            }
        }

        test("create rejects an unknown server") {
            shouldThrow<NotFoundException> {
                service.create(Uuid.random(), CreateScheduledJobRequest(ScheduledJobType.START, "* * * * *"))
            }
        }

        test("RCON_COMMAND requires a command payload") {
            val serverId = createServer(createNode())

            shouldThrow<UnprocessableException> {
                service.create(serverId, CreateScheduledJobRequest(ScheduledJobType.RCON_COMMAND, "* * * * *"))
            }
        }

        test("RCON_COMMAND sanitizes newlines out of the command") {
            val serverId = createServer(createNode())

            val created = service.create(serverId, CreateScheduledJobRequest(ScheduledJobType.RCON_COMMAND, "* * * * *", payload = "say\nhello"))

            created.payload shouldBe "say hello"
        }

        test("RCON_COMMAND rejects an over-long command") {
            val serverId = createServer(createNode())

            shouldThrow<UnprocessableException> {
                service.create(
                    serverId,
                    CreateScheduledJobRequest(ScheduledJobType.RCON_COMMAND, "* * * * *", payload = "x".repeat(ScheduledJobService.MAX_PAYLOAD_LENGTH + 1))
                )
            }
        }

        test("non-RCON types ignore a supplied payload") {
            val serverId = createServer(createNode())

            val created = service.create(serverId, CreateScheduledJobRequest(ScheduledJobType.STOP, "* * * * *", payload = "ignored"))

            created.payload shouldBe null
        }

        test("update changes cron, payload and enabled") {
            val serverId = createServer(createNode())
            val created = service.create(serverId, CreateScheduledJobRequest(ScheduledJobType.RCON_COMMAND, "* * * * *", payload = "say hi"))

            val updated = service.update(
                serverId,
                Uuid.parse(created.id),
                UpdateScheduledJobRequest(cronExpression = "0 */6 * * *", payload = "say bye", enabled = false)
            )

            updated.cronExpression shouldBe "0 */6 * * *"
            updated.payload shouldBe "say bye"
            updated.enabled shouldBe false
        }

        test("update rejects an invalid cron expression") {
            val serverId = createServer(createNode())
            val created = service.create(serverId, CreateScheduledJobRequest(ScheduledJobType.START, "* * * * *"))

            shouldThrow<UnprocessableException> {
                service.update(serverId, Uuid.parse(created.id), UpdateScheduledJobRequest(cronExpression = "nope"))
            }
        }

        test("delete removes the job") {
            val serverId = createServer(createNode())
            val created = service.create(serverId, CreateScheduledJobRequest(ScheduledJobType.START, "* * * * *"))

            service.delete(serverId, Uuid.parse(created.id))

            service.list(serverId) shouldBe emptyList()
        }

        test("a job of another server is not addressable") {
            val serverId = createServer(createNode())
            val otherServerId = createServer(createNode())
            val created = service.create(serverId, CreateScheduledJobRequest(ScheduledJobType.START, "* * * * *"))

            shouldThrow<NotFoundException> { service.delete(otherServerId, Uuid.parse(created.id)) }
        }

        test("availableTypes excludes BACKUP") {
            service.availableTypes() shouldContainExactly listOf(
                ScheduledJobType.START,
                ScheduledJobType.STOP,
                ScheduledJobType.RESTART,
                ScheduledJobType.RCON_COMMAND
            )
        }
    })
