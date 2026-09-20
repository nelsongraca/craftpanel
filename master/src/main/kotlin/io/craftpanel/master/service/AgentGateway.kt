package io.craftpanel.master.service

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.proto.MasterMessage
import kotlinx.coroutines.flow.SharedFlow

interface AgentGateway {

    fun sendToNode(nodeId: String, msg: MasterMessage): Boolean

    /**
     * Suspending variant for latency-critical, non-lossy sends (console attach/input/detach).
     * Waits for buffer space instead of dropping when the outbound channel is momentarily full.
     * Returns false only when the node is not connected.
     */
    suspend fun sendToNodeSuspending(nodeId: String, msg: MasterMessage): Boolean

    /**
     * Push the full symlink/data-dir-override snapshot for a node. Master re-sends this on every
     * reconnect; call it after an admin changes a server's data directory name so the agent's
     * path registry stays in step for stopped servers.
     */
    fun rebuildSymlinks(nodeId: String)

    val agentEvents: SharedFlow<AgentEvent>
}
