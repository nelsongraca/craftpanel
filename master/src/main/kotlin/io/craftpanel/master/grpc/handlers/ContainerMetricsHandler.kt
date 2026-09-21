package io.craftpanel.master.grpc.handlers

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.proto.AgentMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory

class ContainerMetricsHandler(private val agentEvents: MutableSharedFlow<AgentEvent>) {

    private val log = LoggerFactory.getLogger(ContainerMetricsHandler::class.java)

    suspend fun handle(msg: AgentMessage) {
        if (!msg.hasContainerMetrics()) {
            log.warn("ContainerMetricsHandler called with non-containerMetrics message: ${msg.payloadCase}")
            return
        }
        val containerMetrics = msg.containerMetrics
        val recordedAt = recordedAtOrNow(containerMetrics.hasRecordedAt(), containerMetrics.recordedAt)
        val containerMetricEvent = AgentEvent.ContainerMetricsEvent(
            serverId = containerMetrics.serverId,
            cpuPercent = containerMetrics.cpuPercent,
            ramUsedMb = containerMetrics.ramUsedMb,
            netInBytes = containerMetrics.netInBytes,
            netOutBytes = containerMetrics.netOutBytes,
            blockInBytes = containerMetrics.blockInBytes,
            blockOutBytes = containerMetrics.blockOutBytes,
            recordedAt = recordedAt
        )
        // Telemetry must never suspend the control-stream collector: a lagging subscriber (DB
        // persistence, WS fan-out) would otherwise delay console I/O sharing the same stream.
        agentEvents.tryEmitTelemetry(containerMetricEvent, log, containerMetrics.serverId)
    }
}
