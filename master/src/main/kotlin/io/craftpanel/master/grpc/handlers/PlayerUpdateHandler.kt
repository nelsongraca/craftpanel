package io.craftpanel.master.grpc.handlers

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.proto.AgentMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory

class PlayerUpdateHandler(private val agentEvents: MutableSharedFlow<AgentEvent>) {

    private val log = LoggerFactory.getLogger(PlayerUpdateHandler::class.java)

    suspend fun handle(msg: AgentMessage) {
        if (!msg.hasPlayerUpdate()) {
            log.warn("PlayerUpdateHandler called with non-playerUpdate message: ${msg.payloadCase}")
            return
        }
        val playerUpdate = msg.playerUpdate
        val recordedAt = recordedAtOrNow(playerUpdate.hasRecordedAt(), playerUpdate.recordedAt)
        val playerUpdateEvent = AgentEvent.PlayerUpdateEvent(
            serverId = playerUpdate.serverId,
            playerCount = playerUpdate.playerCount,
            playerNames = playerUpdate.playerNamesList,
            recordedAt = recordedAt
        )
        agentEvents.tryEmitTelemetry(playerUpdateEvent, log, playerUpdate.serverId)
    }
}
