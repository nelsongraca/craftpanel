package io.craftpanel.master

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.service.AgentGateway
import io.craftpanel.proto.MasterMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class TestAgentGateway(override val agentEvents: SharedFlow<AgentEvent> = MutableSharedFlow(), private val sendResult: Boolean = true) : AgentGateway {

    val sent = mutableListOf<Pair<String, MasterMessage>>()

    override fun sendToNode(nodeId: String, msg: MasterMessage): Boolean {
        sent.add(nodeId to msg)
        return sendResult
    }
}
