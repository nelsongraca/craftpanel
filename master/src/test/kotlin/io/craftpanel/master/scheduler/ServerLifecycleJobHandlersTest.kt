package io.craftpanel.master.scheduler

import io.craftpanel.master.service.ConflictException
import io.craftpanel.master.service.ServerLifecycleService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ServerLifecycleJobHandlersTest :
    FunSpec({

        test("start handler invokes startServer") {
            val lifecycle = mockk<ServerLifecycleService>()
            coEvery { lifecycle.startServer(any()) } returns Unit
            val serverId = Uuid.random()

            runTest { ServerStartJobHandler(lifecycle).execute(JobExecutionContext(serverId, jobId = null, scheduledAt = Clock.System.now())) }

            coVerify(exactly = 1) { lifecycle.startServer(serverId) }
        }

        test("stop handler invokes stopServer") {
            val lifecycle = mockk<ServerLifecycleService>()
            coEvery { lifecycle.stopServer(any()) } returns Unit
            val serverId = Uuid.random()

            runTest { ServerStopJobHandler(lifecycle).execute(JobExecutionContext(serverId, jobId = null, scheduledAt = Clock.System.now())) }

            coVerify(exactly = 1) { lifecycle.stopServer(serverId) }
        }

        test("restart handler invokes restartServer") {
            val lifecycle = mockk<ServerLifecycleService>()
            coEvery { lifecycle.restartServer(any()) } returns Unit
            val serverId = Uuid.random()

            runTest { ServerRestartJobHandler(lifecycle).execute(JobExecutionContext(serverId, jobId = null, scheduledAt = Clock.System.now())) }

            coVerify(exactly = 1) { lifecycle.restartServer(serverId) }
        }

        test("conflict is swallowed rather than thrown") {
            val lifecycle = mockk<ServerLifecycleService>()
            coEvery { lifecycle.startServer(any()) } throws ConflictException("Server is already running")

            runTest {
                // should not throw
                ServerStartJobHandler(lifecycle).execute(JobExecutionContext(Uuid.random(), jobId = null, scheduledAt = Clock.System.now()))
            }
        }

        test("failure is swallowed rather than thrown") {
            val lifecycle = mockk<ServerLifecycleService>()
            coEvery { lifecycle.restartServer(any()) } throws RuntimeException("agent down")

            runTest {
                // should not throw
                ServerRestartJobHandler(lifecycle).execute(JobExecutionContext(Uuid.random(), jobId = null, scheduledAt = Clock.System.now()))
            }
        }

        test("handler job types match the persisted schedule types") {
            val lifecycle = mockk<ServerLifecycleService>()
            ServerStartJobHandler(lifecycle).jobType shouldBe "START"
            ServerStopJobHandler(lifecycle).jobType shouldBe "STOP"
            ServerRestartJobHandler(lifecycle).jobType shouldBe "RESTART"
        }
    })
