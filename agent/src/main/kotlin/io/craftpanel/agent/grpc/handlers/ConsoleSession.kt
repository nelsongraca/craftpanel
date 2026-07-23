package io.craftpanel.agent.grpc.handlers

import kotlinx.coroutines.flow.Flow
import java.io.Closeable

interface ConsoleSession : Closeable {
    val output: Flow<ByteArray>

    fun writeInput(data: ByteArray)

    fun interface Factory {
        suspend fun create(serverId: String): ConsoleSession?
    }
}
