package io.craftpanel.master.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.plus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class ConsoleSessionManagerTest :
    FunSpec({

        fun newManager(grace: Duration = 20.milliseconds) = ConsoleSessionManager(
            openConsole = { _, _ -> flow<ByteArray> { awaitCancellation() } },
            scope = CoroutineScope(SupervisorJob().plus(Dispatchers.IO)),
            sessionGracePeriod = grace
        )

        test("second getOrCreate reuses session and increments viewerCount") {
            val manager = newManager()
            val serverId = Uuid.random()

            val first = manager.getOrCreate(serverId)
            val second = manager.getOrCreate(serverId)

            second shouldBe first
            second.viewerCount.get() shouldBe 2
        }

        test("releaseViewer above zero keeps session") {
            val manager = newManager()
            val serverId = Uuid.random()

            val session = manager.getOrCreate(serverId)
            manager.getOrCreate(serverId)
            manager.releaseViewer(serverId)

            manager.getOrCreate(serverId) shouldBe session
        }

        test("releaseViewer to zero removes session and cancels job") {
            val manager = newManager()
            val serverId = Uuid.random()

            val session = manager.getOrCreate(serverId)
            manager.releaseViewer(serverId)
            delay(300)

            session.job?.isCancelled shouldBe true
        }

        test("releaseViewer to zero keeps session alive during the grace period") {
            val manager = newManager(grace = 5.seconds)
            val serverId = Uuid.random()

            val session = manager.getOrCreate(serverId)
            manager.releaseViewer(serverId)
            delay(20)

            manager.getOrCreate(serverId) shouldBe session
        }

        test("getOrCreate after full teardown creates fresh session") {
            val manager = newManager()
            val serverId = Uuid.random()

            val first = manager.getOrCreate(serverId)
            manager.releaseViewer(serverId)
            delay(300)
            val second = manager.getOrCreate(serverId)

            second shouldNotBe first
            second.viewerCount.get() shouldBe 1
        }
    })
