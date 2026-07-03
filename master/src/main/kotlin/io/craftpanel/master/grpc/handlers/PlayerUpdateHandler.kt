package io.craftpanel.master.grpc.handlers

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.proto.AgentMessage
import io.craftpanel.proto.PlayerUpdate
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Instant

class PlayerUpdateHandler(
    private val agentEvents: MutableSharedFlow<AgentEvent>,
) {
    private val log = LoggerFactory.getLogger(PlayerUpdateHandler::class.java)

    suspend fun handle(msg: AgentMessage, nodeId: String) {
        if (!msg.hasPlayerUpdate()) {
            log.warn("PlayerUpdateHandler called with non-playerUpdate message: ${msg.payloadCase}")
            return
        }
        val playerUpdate = msg.playerUpdate
        val recordedAt = if (playerUpdate.hasRecordedAt()) {
            Instant.fromEpochSeconds(playerUpdate.recordedAt.seconds, playerUpdate.recordedAt.nanos.toLong())
        } else {
            Clock.System.now()
        }
        val playerUpdateEvent = AgentEvent.PlayerUpdateEvent(
            serverId = playerUpdate.serverId,
            playerCount = playerUpdate.playerCount,
            playerNames = playerUpdate.playerNamesList,
            recordedAt = recordedAt,
        )
        agentEvents.emit(playerUpdateEvent)
    }
}
