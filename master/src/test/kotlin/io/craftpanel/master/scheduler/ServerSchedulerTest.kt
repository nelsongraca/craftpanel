package io.craftpanel.master.scheduler

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.database.schema.ServerJobs
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.database.schema.Nodes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.coEvery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)
class ServerSchedulerTest : FunSpec({

    beforeTest {
        TestDatabase.initIfNeeded()
        TestDatabase.reset()
    }

    // ── fires() ──────────────────────────────────────────────────────────────

    test("fires returns true when cron matches ZonedDateTime") {
        val scheduler = ServerScheduler(emptyMap(), TestScope())
        // "* * * * *" matches every minute
        val at = ZonedDateTime.of(2025, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC)
        scheduler.fires("* * * * *", at) shouldBe true
    }

    test("fires returns false when cron does not match") {
        val scheduler = ServerScheduler(emptyMap(), TestScope())
        // "0 3 * * *" = 03:00 every day; test at 12:00
        val at = ZonedDateTime.of(2025, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC)
        scheduler.fires("0 3 * * *", at) shouldBe false
    }

    test("fires returns false for malformed cron expression") {
        val scheduler = ServerScheduler(emptyMap(), TestScope())
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

    test("tick fires backup handler for matching server schedule") {
        val handler = mockk<ScheduledJobHandler>()
        coEvery { handler.execute(any()) } returns Unit

        val scope = TestScope()
        val scheduler = ServerScheduler(mapOf("BACKUP" to handler), scope)

        val nodeId = createNode()
        createServer(nodeId, "* * * * *")

        val now = kotlin.time.Clock.System.now()
        runTest { scheduler.tick(now) }

        coVerify(exactly = 1) { handler.execute(any()) }
    }

    test("tick does not re-fire backup handler in same minute") {
        val handler = mockk<ScheduledJobHandler>()
        coEvery { handler.execute(any()) } returns Unit

        val scope = TestScope()
        val scheduler = ServerScheduler(mapOf("BACKUP" to handler), scope)

        val nodeId = createNode()
        createServer(nodeId, "* * * * *")

        val now = kotlin.time.Clock.System.now()
        runTest {
            scheduler.tick(now)
            scheduler.tick(now) // same instant = same minute
        }

        coVerify(exactly = 1) { handler.execute(any()) }
    }

    test("tick does not fire handler when cron does not match") {
        val handler = mockk<ScheduledJobHandler>()

        val scope = TestScope()
        val scheduler = ServerScheduler(mapOf("BACKUP" to handler), scope)

        val nodeId = createNode()
        // cron fires only at 03:00 — test uses current time which is unlikely to be 03:00
        createServer(nodeId, "0 3 * * *")

        // Use a specific non-matching time: noon UTC
        val atNoon = kotlin.time.Instant.fromEpochMilliseconds(
            ZonedDateTime.of(2025, 6, 1, 12, 30, 0, 0, ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        )
        runTest { scheduler.tick(atNoon) }

        coVerify(exactly = 0) { handler.execute(any()) }
    }

    test("tick fires generic job handler for matching ServerJob") {
        val handler = mockk<ScheduledJobHandler>()
        coEvery { handler.execute(any()) } returns Unit

        val scope = TestScope()
        val scheduler = ServerScheduler(mapOf("MY_JOB" to handler), scope)

        val nodeId = createNode()
        val serverId = createServer(nodeId, null)

        transaction {
            ServerJobs.insert {
                it[ServerJobs.serverId] = serverId
                it[ServerJobs.type] = "MY_JOB"
                it[ServerJobs.cronExpression] = "* * * * *"
                it[ServerJobs.enabled] = true
            }
        }

        val now = kotlin.time.Clock.System.now()
        runTest { scheduler.tick(now) }

        coVerify(exactly = 1) { handler.execute(any()) }
    }

    test("tick with missing handler for job type does not throw") {
        val scope = TestScope()
        val scheduler = ServerScheduler(emptyMap(), scope) // no handlers

        val nodeId = createNode()
        val serverId = createServer(nodeId, null)

        transaction {
            ServerJobs.insert {
                it[ServerJobs.serverId] = serverId
                it[ServerJobs.type] = "UNKNOWN"
                it[ServerJobs.cronExpression] = "* * * * *"
                it[ServerJobs.enabled] = true
            }
        }

        val now = kotlin.time.Clock.System.now()
        runTest { scheduler.tick(now) } // should not throw
    }

    // ── start/stop ───────────────────────────────────────────────────────────

    test("stop cancels the running job") {
        val scope = TestScope()
        val scheduler = ServerScheduler(emptyMap(), scope)

        scheduler.start()
        scheduler.stop()

        // After stop, advancing time should not cause errors
        runTest {
            advanceTimeBy(61.seconds)
        }
    }
})
