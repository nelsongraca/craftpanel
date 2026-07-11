package io.craftpanel.master.grpc.handlers

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.proto.AgentMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Instant

class BackupHandler(private val agentEvents: MutableSharedFlow<AgentEvent>) {

    private val log = LoggerFactory.getLogger(BackupHandler::class.java)

    suspend fun handleBackupProgress(msg: AgentMessage, nodeId: String) {
        if (!msg.hasBackupProgress()) {
            log.warn("handleBackupProgress called with non-backupProgress message: ${msg.payloadCase}")
            return
        }
        agentEvents.emit(
            AgentEvent.BackupProgressEvent(
                serverId = msg.backupProgress.serverId,
                backupId = msg.backupProgress.backupId,
                percentComplete = msg.backupProgress.percentComplete
            )
        )
    }

    suspend fun handleBackupComplete(msg: AgentMessage, nodeId: String) {
        if (!msg.hasBackupComplete()) {
            log.warn("handleBackupComplete called with non-backupComplete message: ${msg.payloadCase}")
            return
        }
        val completedAt = if (msg.backupComplete.hasCompletedAt()) {
            Instant.fromEpochSeconds(msg.backupComplete.completedAt.seconds, msg.backupComplete.completedAt.nanos.toLong())
        } else {
            Clock.System.now()
        }
        val backupCompleteEvent = AgentEvent.BackupCompleteEvent(
            serverId = msg.backupComplete.serverId,
            backupId = msg.backupComplete.backupId,
            success = msg.backupComplete.success,
            sizeBytes = msg.backupComplete.sizeBytes,
            errorMessage = if (!msg.backupComplete.success) msg.backupComplete.errorMessage else "",
            completedAt = completedAt
        )
        agentEvents.emit(backupCompleteEvent)
    }
}
