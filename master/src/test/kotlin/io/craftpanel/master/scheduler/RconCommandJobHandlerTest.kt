package io.craftpanel.master.scheduler

import io.craftpanel.master.service.AgentGateway
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.fakeServerView
import io.craftpanel.proto.MasterMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class RconCommandJobHandlerTest :
    FunSpec({

        test("sends an RCON command for a healthy server") {
            val serverId = Uuid.random()
            val nodeId = Uuid.random()
            val serverRepository = mockk<ServerRepository>()
            every { serverRepository.findById(serverId) } returns fakeServerView(id = serverId, nodeId = nodeId, status = "HEALTHY")
            val gateway = mockk<AgentGateway>()
            val message = slot<MasterMessage>()
            every { gateway.sendToNode(nodeId.toString(), capture(message)) } returns true

            runTest {
                RconCommandJobHandler(serverRepository, gateway).execute(
                    JobExecutionContext(serverId, jobId = null, scheduledAt = Clock.System.now(), payload = "say hi")
                )
            }

            message.captured.sendRcon.serverId shouldBe serverId.toString()
            message.captured.sendRcon.command shouldBe "say hi"
        }

        test("skips a server that is not healthy") {
            val serverId = Uuid.random()
            val serverRepository = mockk<ServerRepository>()
            every { serverRepository.findById(serverId) } returns fakeServerView(id = serverId, status = "STOPPED")
            val gateway = mockk<AgentGateway>()

            runTest {
                RconCommandJobHandler(serverRepository, gateway).execute(
                    JobExecutionContext(serverId, jobId = null, scheduledAt = Clock.System.now(), payload = "say hi")
                )
            }

            verify(exactly = 0) { gateway.sendToNode(any(), any()) }
        }

        test("skips a job with no command") {
            val serverRepository = mockk<ServerRepository>()
            val gateway = mockk<AgentGateway>()

            runTest {
                RconCommandJobHandler(serverRepository, gateway).execute(
                    JobExecutionContext(Uuid.random(), jobId = null, scheduledAt = Clock.System.now(), payload = "  ")
                )
            }

            verify(exactly = 0) { gateway.sendToNode(any(), any()) }
        }

        test("does not throw when the agent is disconnected") {
            val serverId = Uuid.random()
            val serverRepository = mockk<ServerRepository>()
            every { serverRepository.findById(serverId) } returns fakeServerView(id = serverId, status = "HEALTHY")
            val gateway = mockk<AgentGateway>()
            every { gateway.sendToNode(any(), any()) } returns false

            runTest {
                // should not throw
                RconCommandJobHandler(serverRepository, gateway).execute(
                    JobExecutionContext(serverId, jobId = null, scheduledAt = Clock.System.now(), payload = "say hi")
                )
            }
        }

        test("job type is RCON_COMMAND") {
            RconCommandJobHandler(mockk<ServerRepository>(), mockk<AgentGateway>()).jobType shouldBe "RCON_COMMAND"
        }
    })
