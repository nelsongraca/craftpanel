package io.craftpanel.master.service

import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.domain.DesiredStatus
import io.craftpanel.master.service.repo.ServerRepository
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * The single owner of `servers.desired_status` writes and of the "record intent → send → revert on
 * failure" invariant. Bookkeeping only — no proto or spec knowledge, and no dependency on
 * [ContainerLifecycle], which calls back into it.
 */
class ServerIntent(private val serverRepository: ServerRepository) {

    /** Records [desired] as master's intent. For callers that do not need revert (reconnect/boot sync). */
    fun record(serverId: Uuid, desired: DesiredStatus) {
        setDesiredStatus(serverId, desired.toDb())
    }

    /**
     * Records [desired], runs [action]; reverts the previous intent on any failure and rethrows.
     * A `false` return means the agent is not connected.
     */
    suspend fun withIntent(serverId: Uuid, desired: DesiredStatus, action: suspend () -> Boolean) {
        val previous = serverRepository.findById(serverId)?.desiredStatus
        setDesiredStatus(serverId, desired.toDb())
        try {
            if (!action()) throw BadGatewayException("Agent not connected")
        }
        catch (e: Exception) {
            setDesiredStatus(serverId, previous)
            throw e
        }
    }

    private fun setDesiredStatus(id: Uuid, value: String?) {
        transaction {
            Server.findById(id)
                ?.let { it.desiredStatus = value }
        }
    }
}
