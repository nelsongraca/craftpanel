package io.craftpanel.master.domain

import io.craftpanel.proto.ServerStatusUpdate
import kotlinx.serialization.Serializable

@Serializable
enum class ServerStatus {
    STOPPED, STARTING, HEALTHY, STOPPING, UNHEALTHY;

    val isRunning get() = this in setOf(HEALTHY, STARTING, UNHEALTHY)
    val isStopped get() = this == STOPPED

    fun toDb() = name

    companion object {

        fun fromDb(s: String) = valueOf(s)

        fun fromProto(p: ServerStatusUpdate.ServerStatus): ServerStatus = when (p) {
            ServerStatusUpdate.ServerStatus.STOPPED   -> STOPPED
            ServerStatusUpdate.ServerStatus.STARTING  -> STARTING
            ServerStatusUpdate.ServerStatus.HEALTHY   -> HEALTHY
            ServerStatusUpdate.ServerStatus.UNHEALTHY -> UNHEALTHY
            else                                      -> error("Unspecified or unrecognized proto ServerStatus: $p")
        }
    }
}
