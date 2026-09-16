package io.craftpanel.master.service

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.proto.MasterMessage
import kotlinx.coroutines.flow.SharedFlow

interface AgentGateway {

    fun sendToNode(nodeId: String, msg: MasterMessage): Boolean

    /**
     * Push the full symlink/data-dir-override snapshot for a node. Master re-sends this on every
     * reconnect; call it after an admin changes a server's data directory name so the agent's
     * path registry stays in step for stopped servers.
     */
    fun rebuildSymlinks(nodeId: String)

    val agentEvents: SharedFlow<AgentEvent>
}
