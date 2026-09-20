package io.craftpanel.agent.grpc.handlers

import com.google.protobuf.ByteString
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.proto.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

class ConsoleHandler(private val sessionFactory: ConsoleSession.Factory, private val logFetcher: LogFetcher, private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {

    private val log = LoggerFactory.getLogger(ConsoleHandler::class.java)

    /**
     * A console session slot. Registered synchronously at attach time — before the (slow) Docker
     * attach completes — so input arriving concurrently with attach is buffered rather than dropped.
     */
    private class SessionEntry {
        @Volatile
        var session: ConsoleSession? = null

        /** Input received while the attach is still in flight, drained in order once ready. */
        val pendingInput = LinkedBlockingQueue<ByteArray>(PENDING_INPUT_CAPACITY)

        var job: Job? = null
    }

    private val consoleSessions = ConcurrentHashMap<String, SessionEntry>()

    suspend fun handleConsoleAttach(cmd: ConsoleAttach, out: AgentOutbound) {
        val reqId = cmd.requestId
        val entry = SessionEntry()
        if (consoleSessions.putIfAbsent(reqId, entry) != null) {
            log.warn("Console attach for already-active session $reqId — ignoring")
            return
        }

        val session = sessionFactory.create(cmd.serverId)
        if (session == null) {
            consoleSessions.remove(reqId, entry)
            out.send {
                consoleOutput = consoleOutput {
                    requestId = reqId
                    closed = true
                }
            }
            return
        }

        entry.session = session

        // Drain anything queued while attach was in flight, preserving keystroke order.
        while (true) {
            val queued = entry.pendingInput.poll() ?: break
            runCatching { session.writeInput(queued) }
                .onFailure { log.warn("Console input drain failed (session=$reqId)", it) }
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
                consoleSessions.remove(reqId, entry)
                session.close()
            }
        }

        entry.job = job
    }

    fun handleConsoleInput(cmd: ConsoleInput) {
        val entry = consoleSessions[cmd.requestId] ?: return
        if (cmd.data.size() == 0) return

        val bytes = cmd.data.toByteArray()
        val session = entry.session
        if (session != null) {
            runCatching {
                session.writeInput(bytes)
            }.onFailure { log.warn("Console input write failed (session=${cmd.requestId})", it) }
        } else if (!entry.pendingInput.offer(bytes)) {
            // Attach still in flight and the buffer is full — drop rather than block the stream.
            log.warn("Console input buffer full during attach — dropping ${bytes.size} byte(s) (session=${cmd.requestId})")
        }
    }

    fun handleConsoleDetach(cmd: ConsoleDetach) {
        val entry = consoleSessions.remove(cmd.requestId) ?: return
        entry.job?.cancel()
        entry.session?.close()
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

    companion object {
        internal const val PENDING_INPUT_CAPACITY = 64
    }
}
