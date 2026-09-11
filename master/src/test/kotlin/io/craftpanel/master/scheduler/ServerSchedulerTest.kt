package io.craftpanel.master.scheduler

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.schema.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)
class ServerSchedulerTest :
    FunSpec({

        val repos = TestRepositories()
        val serverRepository = repos.serverRepository
        val lifecycleService = mockk<io.craftpanel.master.service.ServerLifecycleService>()

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        // ── fires() ──────────────────────────────────────────────────────────────

        test("fires returns true when cron matches ZonedDateTime") {
            val scheduler = ServerScheduler(emptyMap(), TestScope(), serverRepository, repos.serverJobRepository, lifecycleService)
            // "* * * * *" matches every minute
            val at = ZonedDateTime.of(2025, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC)
            scheduler.fires("* * * * *", at) shouldBe true
        }

        test("fires returns false when cron does not match") {
            val scheduler = ServerScheduler(emptyMap(), TestScope(), serverRepository, repos.serverJobRepository, lifecycleService)
            // "0 3 * * *" = 03:00 every day; test at 12:00
            val at = ZonedDateTime.of(2025, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC)
            scheduler.fires("0 3 * * *", at) shouldBe false
        }

        test("fires returns false for malformed cron expression") {
            val scheduler = ServerScheduler(emptyMap(), TestScope(), serverRepository, repos.serverJobRepository, lifecycleService)
            val at = ZonedDateTime.of(2025, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC)
            scheduler.fires("not-a-cron", at) shouldBe false
        }

        // ── tick() with DB ────────────────────────────────────────────────────────

        fun createNode(): Uuid = transaction {
            Nodes.insert {
                it[Nodes.hostname] = "node-${Uuid.random()}"
                it[Nodes.displayName] = "Test"
                it[Nodes.publicIp] = "1.2.3.4"
                it[Nodes.privateIp] = "10.0.0.1"
                it[Nodes.tokenHash] = "a".repeat(64)
                it[Nodes.status] = "ACTIVE"
                it[Nodes.health] = "HEALTHY"
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        fun createServer(nodeId: Uuid, schedule: String?): Uuid = transaction {
            Servers.insert {
                it[Servers.nodeId] = nodeId
                it[Servers.name] = "srv-${Uuid.random()}"
                it[Servers.hostPort] = 25565
                it[Servers.memoryMb] = 1024
                it[Servers.backupSchedule] = schedule
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }

        fun createServerWithState(
            nodeId: Uuid,
            status: String,
            expiresAt: kotlinx.datetime.LocalDateTime? = null
        ): Uuid = transaction {
            Servers.insert {
                it[Servers.nodeId] = nodeId
                it[Servers.name] = "srv-${Uuid.random()}"
                it[Servers.hostPort] = 25565
                it[Servers.memoryMb] = 1024
                it[Servers.status] = status
                it[Servers.expiresAt] = expiresAt
            }[Servers.id].let { Uuid.parse(it.toString()) }
        }

        test("tick fires backup handler for matching server schedule") {
            val handler = mockk<ScheduledJobHandler>()
            coEvery { handler.execute(any()) } returns Unit

            val nodeId = createNode()
            createServer(nodeId, "* * * * *")

            val now = kotlin.time.Clock.System.now()
            runTest {
                val scheduler = ServerScheduler(mapOf("BACKUP" to handler), this, serverRepository, repos.serverJobRepository, lifecycleService)
                scheduler.tick(now)
            }

            coVerify(exactly = 1) { handler.execute(any()) }
        }

        test("tick does not re-fire backup handler in same minute") {
            val handler = mockk<ScheduledJobHandler>()
            coEvery { handler.execute(any()) } returns Unit

            val nodeId = createNode()
            createServer(nodeId, "* * * * *")

            val now = kotlin.time.Clock.System.now()
            runTest {
                val scheduler = ServerScheduler(mapOf("BACKUP" to handler), this, serverRepository, repos.serverJobRepository, lifecycleService)
                scheduler.tick(now)
                scheduler.tick(now) // same instant = same minute
            }

            coVerify(exactly = 1) { handler.execute(any()) }
        }

        test("tick does not fire handler when cron does not match") {
            val handler = mockk<ScheduledJobHandler>()

            val nodeId = createNode()
            // cron fires only at 03:00 — test uses current time which is unlikely to be 03:00
            createServer(nodeId, "0 3 * * *")

            // Use a specific non-matching time: noon UTC
            val atNoon = kotlin.time.Instant.fromEpochMilliseconds(
                ZonedDateTime.of(2025, 6, 1, 12, 30, 0, 0, ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            )
            runTest {
                val scheduler = ServerScheduler(mapOf("BACKUP" to handler), this, serverRepository, repos.serverJobRepository, lifecycleService)
                scheduler.tick(atNoon)
            }

            coVerify(exactly = 0) { handler.execute(any()) }
        }

        test("tick fires generic job handler for matching ServerJob") {
            val handler = mockk<ScheduledJobHandler>()
            coEvery { handler.execute(any()) } returns Unit

            val nodeId = createNode()
            val serverId = createServer(nodeId, null)

            transaction {
                ServerJobs.insert {
                    it[ServerJobs.serverId] = EntityID(serverId, Servers)
                    it[ServerJobs.type] = "MY_JOB"
                    it[ServerJobs.cronExpression] = "* * * * *"
                    it[ServerJobs.enabled] = true
                }
            }

            val now = kotlin.time.Clock.System.now()
            runTest {
                val scheduler = ServerScheduler(mapOf("MY_JOB" to handler), this, serverRepository, repos.serverJobRepository, lifecycleService)
                scheduler.tick(now)
            }

            coVerify(exactly = 1) { handler.execute(any()) }
        }

        test("tick with missing handler for job type does not throw") {
            val scope = TestScope()
            val scheduler = ServerScheduler(emptyMap(), scope, serverRepository, repos.serverJobRepository, lifecycleService) // no handlers

            val nodeId = createNode()
            val serverId = createServer(nodeId, null)

            transaction {
                ServerJobs.insert {
                    it[ServerJobs.serverId] = EntityID(serverId, Servers)
                    it[ServerJobs.type] = "UNKNOWN"
                    it[ServerJobs.cronExpression] = "* * * * *"
                    it[ServerJobs.enabled] = true
                }
            }

            val now = kotlin.time.Clock.System.now()
            runTest { scheduler.tick(now) } // should not throw
        }

        test("tick stops expired running servers, leaves others untouched") {
            val lifecycle = mockk<io.craftpanel.master.service.ServerLifecycleService>()
            every { lifecycle.stopServer(any()) } returns Unit

            val nodeId = createNode()
            val expiredRunning = createServerWithState(nodeId, "HEALTHY", kotlinx.datetime.LocalDateTime(2020, 1, 1, 0, 0, 0))
            val expiredStarting = createServerWithState(nodeId, "STARTING", kotlinx.datetime.LocalDateTime(2020, 1, 1, 0, 0, 0))
            val expiredStopped = createServerWithState(nodeId, "STOPPED", kotlinx.datetime.LocalDateTime(2020, 1, 1, 0, 0, 0))
            val aliveRunning = createServerWithState(nodeId, "HEALTHY", kotlinx.datetime.LocalDateTime(2099, 1, 1, 0, 0, 0))
            val noExpiry = createServerWithState(nodeId, "HEALTHY", null)

            val now = kotlin.time.Clock.System.now()
            runTest {
                ServerScheduler(emptyMap(), this, serverRepository, repos.serverJobRepository, lifecycle).tick(now)
            }

            verify(exactly = 1) { lifecycle.stopServer(expiredRunning) }
            verify(exactly = 1) { lifecycle.stopServer(expiredStarting) }
            verify(exactly = 0) { lifecycle.stopServer(expiredStopped) }
            verify(exactly = 0) { lifecycle.stopServer(aliveRunning) }
            verify(exactly = 0) { lifecycle.stopServer(noExpiry) }
        }

        // ── start/stop ───────────────────────────────────────────────────────────

        test("stop cancels the running job") {
            val scope = TestScope()
            val scheduler = ServerScheduler(emptyMap(), scope, serverRepository, repos.serverJobRepository, lifecycleService)

            scheduler.start()
            scheduler.stop()

            // After stop, advancing time should not cause errors
            runTest {
                advanceTimeBy(61.seconds)
            }
        }
    })
