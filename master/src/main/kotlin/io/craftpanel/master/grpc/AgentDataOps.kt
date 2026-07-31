package io.craftpanel.master.grpc

import io.craftpanel.proto.AgentMessage
import io.craftpanel.proto.ConsoleOutput
import io.craftpanel.proto.MasterMessage
import io.craftpanel.proto.consoleAttach
import io.craftpanel.proto.consoleDetach
import io.craftpanel.proto.consoleInput
import io.craftpanel.proto.fetchContainerLogsRequest
import io.craftpanel.proto.masterMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * RPC-style request/response correlation over the agent-initiated control stream, used by
 * DataServiceProxy for console sessions, file ops, and container-log fetches.
 */
class AgentDataOps(private val dataOpContext: DataOpContext, private val sendToNode: (String, MasterMessage) -> Boolean) {

    private val pendingRequests get() = dataOpContext.pendingRequests
    private val consoleOutputChannels get() = dataOpContext.consoleOutputChannels

    /** Send a MasterMessage and wait for the agent's AgentMessage response. */
    suspend fun sendAndAwait(nodeId: String, reqId: String, msg: MasterMessage, timeoutMs: Long = 30_000): AgentMessage {
        val deferred = CompletableDeferred<AgentMessage>()
        pendingRequests["$nodeId/$reqId"] = deferred
        if (!sendToNode(nodeId, msg)) {
            pendingRequests.remove("$nodeId/$reqId")
            error("Node $nodeId is not connected")
        }
        return try {
            withTimeout(timeoutMs.milliseconds) { deferred.await() }
        } finally {
            pendingRequests.remove("$nodeId/$reqId")
        }
    }

    /** Open a multiplexed console session over the control stream. */
    fun openConsole(nodeId: String, serverId: String, input: Flow<ByteArray>): Flow<ConsoleOutput> = channelFlow {
        val reqId = Uuid.random()
            .toString()
        val outputChannel = Channel<ConsoleOutput>(Channel.BUFFERED)
        consoleOutputChannels["$nodeId/$reqId"] = outputChannel

        if (!sendToNode(
                nodeId,
                masterMessage {
                    consoleAttach = consoleAttach {
                        requestId = reqId
                        this.serverId = serverId
                    }
                }
            )
        ) {
            consoleOutputChannels.remove("$nodeId/$reqId")
            error("Node $nodeId is not connected")
        }

        // Forward browser input to agent
        val inputJob = launch {
            try {
                input.collect { bytes ->
                    sendToNode(
                        nodeId,
                        masterMessage {
                            consoleInput = consoleInput {
                                requestId = reqId
                                data = com.google.protobuf.ByteString.copyFrom(bytes)
                            }
                        }
                    )
                }
            } finally {
                sendToNode(
                    nodeId,
                    masterMessage {
                        consoleDetach = consoleDetach { requestId = reqId }
                    }
                )
            }
        }

        // Forward agent output to caller
        try {
            for (output in outputChannel) {
                send(output)
                if (output.closed) break
            }
        } finally {
            consoleOutputChannels.remove("$nodeId/$reqId")
                ?.close()
            inputJob.cancel()
        }
    }

    /** Fetch static container logs for crash diagnosis. Works for any container state. */
    suspend fun fetchContainerLogs(nodeId: String, serverId: String, tailLines: Int): List<String> {
        val reqId = Uuid.random().toString()
        val response = sendAndAwait(
            nodeId,
            reqId,
            masterMessage {
                fetchContainerLogs = fetchContainerLogsRequest {
                    requestId = reqId
                    this.serverId = serverId
                    this.tailLines = tailLines
                }
            }
        )
        val fetchResponse = response.fetchContainerLogsResponse
        if (fetchResponse.closed) return emptyList()
        return fetchResponse.linesList
    }
}
