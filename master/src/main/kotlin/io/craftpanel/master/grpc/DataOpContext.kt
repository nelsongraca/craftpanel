package io.craftpanel.master.grpc

import io.craftpanel.proto.AgentMessage
import io.craftpanel.proto.ConsoleOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

data class DataOpContext(
    val pendingRequests: ConcurrentHashMap<String, CompletableDeferred<AgentMessage>>,
    val consoleOutputChannels: ConcurrentHashMap<String, Channel<ConsoleOutput>>,
)
