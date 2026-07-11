package io.craftpanel.agent.grpc.handlers

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import com.google.protobuf.ByteString
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.proto.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ConsoleHandler(private val containerManager: ContainerManager) {

    private val log = LoggerFactory.getLogger(ConsoleHandler::class.java)

    // Active console sessions: request_id → (job, inputPipe, detached)
    private val consoleSessions = ConcurrentHashMap<String, Triple<Job, PipedOutputStream, AtomicBoolean>>()

    suspend fun handleConsoleAttach(cmd: ConsoleAttach, out: AgentOutbound) {
        val reqId = cmd.requestId
        if (consoleSessions.containsKey(reqId)) {
            log.warn("Console attach for already-active session $reqId — ignoring")
            return
        }

        val containers = containerManager.listContainers()
        val container = containers.find { it.serverId == cmd.serverId }
        if (container == null) {
            out.send {
                consoleOutput = consoleOutput {
                    requestId = reqId
                    closed = true
                }
            }
            log.warn("Console attach: server ${cmd.serverId} not found")
            return
        }

        if (container.runState != ContainerState.RunState.RUNNING) {
            log.warn("Console attach: server ${cmd.serverId} container is ${container.runState}, not RUNNING")
            out.send {
                consoleOutput = consoleOutput {
                    requestId = reqId
                    closed = true
                }
            }
            return
        }

        val inputPipe = PipedOutputStream()
        val detached = AtomicBoolean(false)

        @Suppress("BlockingMethodInNonBlockingContext")
        val inputStream = PipedInputStream(inputPipe)

        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val callback = object : ResultCallback.Adapter<Frame>() {
                    override fun onNext(frame: Frame) {
                        frame.payload?.takeIf { it.isNotEmpty() }
                            ?.let { payload ->
                                out.tryConsoleOutput(reqId) { data = ByteString.copyFrom(payload) }
                            }
                    }

                    override fun onComplete() {
                        out.tryConsoleOutput(reqId) { closed = true }
                        if (!detached.get()) {
                            out.tryServerStatus(cmd.serverId, ServerStatusUpdate.ServerStatus.STOPPED)
                        }
                        consoleSessions.remove(reqId)
                    }

                    override fun onError(t: Throwable) {
                        log.warn("Console attach error (session=$reqId): ${t.message}")
                        out.tryConsoleOutput(reqId) { closed = true }
                        consoleSessions.remove(reqId)
                    }
                }

                log.info("Attaching console to container ${container.containerName} (session=$reqId)")
                runCatching {
                    containerManager.attachInteractive(container.containerName, inputStream, callback)
                }.onFailure { e ->
                    log.warn("Console attach failed (session=$reqId): ${e.message}")
                    out.tryConsoleOutput(reqId) { closed = true }
                }
            } finally {
                runCatching { inputPipe.close() }
                consoleSessions.remove(reqId)
            }
        }

        consoleSessions[reqId] = Triple(job, inputPipe, detached)
    }

    fun handleConsoleInput(cmd: ConsoleInput) {
        val session = consoleSessions[cmd.requestId] ?: return
        if (cmd.data.size() > 0) {
            runCatching {
                session.second.write(cmd.data.toByteArray())
                session.second.flush()
            }.onFailure { log.warn("Console input write failed (session=${cmd.requestId})", it) }
        }
    }

    fun handleConsoleDetach(cmd: ConsoleDetach) {
        val session = consoleSessions.remove(cmd.requestId) ?: return
        session.third.set(true)
        session.first.cancel()
        runCatching { session.second.close() }
        log.info("Console session ${cmd.requestId} detached")
    }
}
