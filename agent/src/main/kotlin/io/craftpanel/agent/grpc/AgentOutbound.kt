package io.craftpanel.agent.grpc

import io.craftpanel.proto.*
import kotlinx.coroutines.channels.SendChannel

class AgentOutbound(
    private val out: SendChannel<AgentMessage>,
    private val nodeId: String,
) {

    suspend fun serverStatus(
        serverId: String,
        status: ServerStatusUpdate.ServerStatus,
        containerId: String? = null,
    ) {
        val id = nodeId
        out.send(agentMessage {
            this.nodeId = id
            serverStatus = serverStatusUpdate {
                this.serverId = serverId
                this.status = status
                if (containerId != null) this.containerId = containerId
            }
        })
    }

    fun tryServerStatus(
        serverId: String,
        status: ServerStatusUpdate.ServerStatus,
    ) {
        val id = nodeId
        out.trySend(agentMessage {
            this.nodeId = id
            serverStatus = serverStatusUpdate {
                this.serverId = serverId
                this.status = status
            }
        })
    }

    fun tryConsoleOutput(requestId: String, build: ConsoleOutputKt.Dsl.() -> Unit) {
        val id = nodeId
        out.trySend(agentMessage {
            this.nodeId = id
            consoleOutput = consoleOutput {
                this.requestId = requestId
                build()
            }
        })
    }

    suspend fun send(build: AgentMessageKt.Dsl.() -> Unit) {
        val id = nodeId
        out.send(agentMessage {
            this.nodeId = id
            build()
        })
    }

    fun trySend(build: AgentMessageKt.Dsl.() -> Unit) {
        val id = nodeId
        out.trySend(agentMessage {
            this.nodeId = id
            build()
        })
    }
}
