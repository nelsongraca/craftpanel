package io.craftpanel.agent.grpc.handlers

import com.google.protobuf.timestamp
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.proto.ServerStatusUpdate
import org.slf4j.Logger
import java.security.SecureRandom
import java.time.Instant

internal fun nowTimestamp() = timestamp {
    val now = Instant.now()
    seconds = now.epochSecond
    nanos = now.nano
}

internal fun generateRsyncPassword(): String {
    // Alphanumeric charset only — intentional security invariant.
    // The password is echoed unquoted into the rsyncd secrets file and interpolated
    // into a sh -c script; shell metacharacters would cause injection. Do not widen.
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    val random = SecureRandom()
    return (1..32).map { chars[random.nextInt(chars.length)] }
        .joinToString("")
}

internal suspend fun withStatus(
    out: AgentOutbound,
    serverId: String,
    successStatus: ServerStatusUpdate.ServerStatus,
    log: Logger,
    logContext: String,
    block: suspend () -> Unit,
) {
    runCatching { block() }
        .onSuccess { if (serverId.isNotEmpty()) out.serverStatus(serverId, successStatus) }
        .onFailure { e ->
            log.error(logContext, e)
            if (serverId.isNotEmpty()) out.serverStatus(serverId, ServerStatusUpdate.ServerStatus.UNHEALTHY)
        }
}
