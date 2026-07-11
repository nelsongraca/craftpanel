package io.craftpanel.master.service

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.proto.MasterMessage
import kotlinx.coroutines.flow.SharedFlow

interface AgentGateway {

    fun sendToNode(nodeId: String, msg: MasterMessage): Boolean
    val agentEvents: SharedFlow<AgentEvent>
}
