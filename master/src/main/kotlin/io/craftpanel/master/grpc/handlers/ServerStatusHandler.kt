package io.craftpanel.master.grpc.handlers

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.domain.ServerStatus
import io.craftpanel.proto.AgentMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory

class ServerStatusHandler(
    private val agentEvents: MutableSharedFlow<AgentEvent>,
) {
    private val log = LoggerFactory.getLogger(ServerStatusHandler::class.java)

    suspend fun handle(msg: AgentMessage, nodeId: String) {
        if (!msg.hasServerStatus()) {
            log.warn("ServerStatusHandler called with non-serverStatus message: ${msg.payloadCase}")
            return
        }
        val domainStatus = ServerStatus.fromProto(msg.serverStatus.status)
        val serverStatusEvent = AgentEvent.ServerStatusEvent(
            serverId = msg.serverStatus.serverId,
            status = domainStatus,
        )
        agentEvents.emit(serverStatusEvent)
    }
}
