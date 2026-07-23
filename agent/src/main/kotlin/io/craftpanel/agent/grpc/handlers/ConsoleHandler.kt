package io.craftpanel.agent.grpc.handlers

import com.google.protobuf.ByteString
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.proto.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class ConsoleHandler(
    private val sessionFactory: ConsoleSession.Factory,
    private val logFetcher: LogFetcher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val log = LoggerFactory.getLogger(ConsoleHandler::class.java)

    private val consoleSessions = ConcurrentHashMap<String, Pair<Job, ConsoleSession>>()

    suspend fun handleConsoleAttach(cmd: ConsoleAttach, out: AgentOutbound) {
        val reqId = cmd.requestId
        if (consoleSessions.containsKey(reqId)) {
            log.warn("Console attach for already-active session $reqId — ignoring")
            return
        }

        val session = sessionFactory.create(cmd.serverId)
        if (session == null) {
            out.send {
                consoleOutput = consoleOutput {
                    requestId = reqId
                    closed = true
                }
            }
            return
        }

        val job = CoroutineScope(ioDispatcher).launch {
            try {
                session.output.collect { data ->
                    out.tryConsoleOutput(reqId) { this.data = ByteString.copyFrom(data) }
                }
                out.tryConsoleOutput(reqId) { closed = true }
                out.tryServerStatus(cmd.serverId, ServerStatusUpdate.ServerStatus.STOPPED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Console session error (session=$reqId): ${e.message}")
                out.tryConsoleOutput(reqId) { closed = true }
            } finally {
                consoleSessions.remove(reqId)
                session.close()
            }
        }

        consoleSessions[reqId] = job to session
    }

    fun handleConsoleInput(cmd: ConsoleInput) {
        val session = consoleSessions[cmd.requestId]?.second ?: return
        if (cmd.data.size() > 0) {
            runCatching {
                session.writeInput(cmd.data.toByteArray())
            }.onFailure { log.warn("Console input write failed (session=${cmd.requestId})", it) }
        }
    }

    fun handleConsoleDetach(cmd: ConsoleDetach) {
        val entry = consoleSessions.remove(cmd.requestId) ?: return
        entry.first.cancel()
        entry.second.close()
        log.info("Console session ${cmd.requestId} detached")
    }

    suspend fun handleFetchContainerLogs(cmd: FetchContainerLogsRequest, out: AgentOutbound) {
        runCatching {
            val lines = logFetcher.fetchLogs(cmd.serverId, cmd.tailLines)
            if (lines == null) {
                out.send {
                    fetchContainerLogsResponse = fetchContainerLogsResponse {
                        requestId = cmd.requestId
                        closed = true
                    }
                }
                return
            }

            out.send {
                fetchContainerLogsResponse = fetchContainerLogsResponse {
                    requestId = cmd.requestId
                    this.lines.addAll(lines)
                    closed = false
                }
            }
        }.onFailure { e ->
            log.warn("FetchContainerLogs failed for server ${cmd.serverId}: ${e.message}")
            out.trySend {
                fetchContainerLogsResponse = fetchContainerLogsResponse {
                    requestId = cmd.requestId
                    closed = true
                }
            }
        }
    }
}
