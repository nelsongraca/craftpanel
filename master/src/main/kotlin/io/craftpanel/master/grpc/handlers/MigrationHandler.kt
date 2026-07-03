package io.craftpanel.master.grpc.handlers

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.proto.AgentMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory

class MigrationHandler(
    private val agentEvents: MutableSharedFlow<AgentEvent>,
) {
    private val log = LoggerFactory.getLogger(MigrationHandler::class.java)

    suspend fun handleRsyncReady(msg: AgentMessage, nodeId: String) {
        if (!msg.hasRsyncReady()) {
            log.warn("handleRsyncReady called with non-rsyncReady message: ${msg.payloadCase}")
            return
        }
        agentEvents.emit(
            AgentEvent.RsyncReadyEvent(
                migrationId = msg.rsyncReady.migrationId,
                rsyncPassword = msg.rsyncReady.rsyncPassword,
            )
        )
    }

    suspend fun handleRsyncProgress(msg: AgentMessage, nodeId: String) {
        if (!msg.hasRsyncProgress()) {
            log.warn("handleRsyncProgress called with non-rsyncProgress message: ${msg.payloadCase}")
            return
        }
        agentEvents.emit(
            AgentEvent.RsyncProgressEvent(
                migrationId = msg.rsyncProgress.migrationId,
                isFinalPass = msg.rsyncProgress.isFinalPass,
                percentComplete = msg.rsyncProgress.percentComplete,
                bytesTransferred = msg.rsyncProgress.bytesTransferred,
                phase = msg.rsyncProgress.phase,
            )
        )
    }

    suspend fun handleRsyncComplete(msg: AgentMessage, nodeId: String) {
        if (!msg.hasRsyncComplete()) {
            log.warn("handleRsyncComplete called with non-rsyncComplete message: ${msg.payloadCase}")
            return
        }
        agentEvents.emit(
            AgentEvent.RsyncCompleteEvent(
                migrationId = msg.rsyncComplete.migrationId,
                isFinalPass = msg.rsyncComplete.isFinalPass,
                success = msg.rsyncComplete.success,
                errorMessage = msg.rsyncComplete.errorMessage,
            )
        )
    }
}
