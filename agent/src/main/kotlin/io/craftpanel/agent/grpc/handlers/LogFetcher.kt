package io.craftpanel.agent.grpc.handlers

fun interface LogFetcher {
    suspend fun fetchLogs(serverId: String, tailLines: Int): List<String>?
}
