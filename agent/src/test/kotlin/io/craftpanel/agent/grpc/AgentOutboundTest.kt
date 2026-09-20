package io.craftpanel.agent.grpc

import io.craftpanel.proto.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

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
    })
