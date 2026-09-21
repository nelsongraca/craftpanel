package io.craftpanel.agent.grpc

import io.craftpanel.proto.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger

class AgentOutboundTest :
    FunSpec({

        test("sendTelemetry routes to the telemetry lane only") {
            val realtime = Channel<AgentMessage>(Channel.UNLIMITED)
            val telemetry = Channel<AgentMessage>(Channel.UNLIMITED)
            val out = AgentOutbound(realtime, telemetry, "node-1")

            out.sendTelemetry { nodeMetrics = nodeMetricsUpdate { cpuPercent = 42.0 } }
            out.send {
                serverStatus = serverStatusUpdate {
                    serverId = "srv-1"
                    status = ServerStatusUpdate.ServerStatus.HEALTHY
                }
            }

            val telemetryMsg = telemetry.tryReceive()
                .getOrThrow()
            telemetryMsg.hasNodeMetrics() shouldBe true
            telemetry.tryReceive().isFailure shouldBe true

            val realtimeMsg = realtime.tryReceive()
                .getOrThrow()
            realtimeMsg.hasServerStatus() shouldBe true
            realtime.tryReceive().isFailure shouldBe true
        }

        test("sendTelemetry drops oldest instead of blocking when the telemetry lane is saturated") {
            val realtime = Channel<AgentMessage>(Channel.UNLIMITED)
            val telemetry = Channel<AgentMessage>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            val out = AgentOutbound(realtime, telemetry, "node-1")

            out.sendTelemetry { nodeMetrics = nodeMetricsUpdate { cpuPercent = 1.0 } }
            out.sendTelemetry { nodeMetrics = nodeMetricsUpdate { cpuPercent = 2.0 } }
            out.sendTelemetry { nodeMetrics = nodeMetricsUpdate { cpuPercent = 3.0 } }

            val remaining = telemetry.tryReceive()
                .getOrThrow()
            remaining.nodeMetrics.cpuPercent shouldBe 3.0
            telemetry.tryReceive().isFailure shouldBe true
            realtime.tryReceive().isFailure shouldBe true
        }

        test("withStatus emits the success status when the block returns") {
            val realtime = Channel<AgentMessage>(Channel.UNLIMITED)
            val out = AgentOutbound(realtime, "node-1")
            val log = mockk<Logger>(relaxed = true)
            var ran = false

            runBlocking {
                out.withStatus("srv-1", ServerStatusUpdate.ServerStatus.HEALTHY, log, "ctx") { ran = true }
            }

            ran shouldBe true
            realtime.tryReceive()
                .getOrThrow()
                .serverStatus.status shouldBe ServerStatusUpdate.ServerStatus.HEALTHY
        }

        test("withStatus emits UNHEALTHY and logs when the block throws") {
            val realtime = Channel<AgentMessage>(Channel.UNLIMITED)
            val out = AgentOutbound(realtime, "node-1")
            val log = mockk<Logger>(relaxed = true)

            runBlocking {
                out.withStatus("srv-1", ServerStatusUpdate.ServerStatus.HEALTHY, log, "failed to start") {
                    error("boom")
                }
            }

            realtime.tryReceive()
                .getOrThrow()
                .serverStatus.status shouldBe ServerStatusUpdate.ServerStatus.UNHEALTHY
            verify { log.error("failed to start", any<Throwable>()) }
        }

        test("withStatus emits nothing on success when success is null") {
            val realtime = Channel<AgentMessage>(Channel.UNLIMITED)
            val out = AgentOutbound(realtime, "node-1")
            val log = mockk<Logger>(relaxed = true)

            runBlocking {
                out.withStatus("srv-1", null, log, "ctx") { }
            }

            realtime.tryReceive().isFailure shouldBe true
        }
    })
