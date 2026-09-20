package io.craftpanel.master.grpc.handlers

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.proto.AgentMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Instant

class ContainerMetricsHandler(private val agentEvents: MutableSharedFlow<AgentEvent>) {

    private val log = LoggerFactory.getLogger(ContainerMetricsHandler::class.java)

    suspend fun handle(msg: AgentMessage, nodeId: String) {
        if (!msg.hasContainerMetrics()) {
            log.warn("ContainerMetricsHandler called with non-containerMetrics message: ${msg.payloadCase}")
            return
        }
        val containerMetrics = msg.containerMetrics
        val recordedAt = if (containerMetrics.hasRecordedAt()) {
            Instant.fromEpochSeconds(containerMetrics.recordedAt.seconds, containerMetrics.recordedAt.nanos.toLong())
        } else {
            Clock.System.now()
        }
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
        if (!agentEvents.tryEmit(containerMetricEvent)) {
            log.debug("Dropped container metrics event for server {} — agent event buffer full", containerMetrics.serverId)
        }
    }
}
