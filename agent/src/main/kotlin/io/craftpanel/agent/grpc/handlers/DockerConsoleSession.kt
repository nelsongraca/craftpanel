package io.craftpanel.agent.grpc.handlers

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import io.craftpanel.agent.docker.ContainerManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.slf4j.LoggerFactory
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class DockerConsoleSession(containerName: String, containerManager: ContainerManager) : ConsoleSession {

    private val log = LoggerFactory.getLogger(DockerConsoleSession::class.java)

    private val pipeOut = PipedOutputStream()
    private val pipeIn = PipedInputStream(pipeOut)
    private val _output = Channel<ByteArray>(BUFFERED)

    // Bounded, non-blocking input queue. The docker-java attach reader drains stdin only as it
    // services frames, so a direct blocking write here would stall the caller (and, when the
    // caller is the control-stream dispatcher, the whole bidirectional stream). A dedicated
    // pump thread performs the blocking write instead.
    private val inputQueue = LinkedBlockingQueue<ByteArray>(INPUT_QUEUE_CAPACITY)
    private val pumpThread: Thread = thread(
        start = true,
        isDaemon = true,
        name = "console-stdin-${containerName.takeLast(24)}"
    ) {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val chunk = inputQueue.take()
                pipeOut.write(chunk)
                pipeOut.flush()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            log.debug("Console stdin pump stopped (session=$containerName): ${e.message}")
        }
    }

    private val callback = object : ResultCallback.Adapter<Frame>() {
        override fun onNext(frame: Frame) {
            frame.payload?.takeIf { it.isNotEmpty() }?.let { _output.trySend(it) }
        }

        override fun onComplete() {
            _output.close()
        }

        override fun onError(t: Throwable) {
            _output.close(t)
        }
    }

    override val output: Flow<ByteArray> = _output.receiveAsFlow()

    init {
        containerManager.attachInteractive(containerName, pipeIn, callback)
    }

    override fun writeInput(data: ByteArray) {
        if (!inputQueue.offer(data)) {
            log.warn("Console input queue full — dropping ${data.size} byte(s)")
        }
    }

    override fun close() {
        callback.close()
        pumpThread.interrupt()
        runCatching { pipeOut.close() }
    }

    class Factory(private val containerManager: ContainerManager) : ConsoleSession.Factory {
        private val log = LoggerFactory.getLogger(Factory::class.java)

        override suspend fun create(serverId: String): ConsoleSession? {
            val containers = containerManager.listContainers()
            val container = containers.find { it.serverId == serverId }
            if (container == null) {
                log.warn("Console attach: server $serverId not found")
                return null
            }
            if (container.runState != io.craftpanel.proto.ContainerState.RunState.RUNNING) {
                log.warn("Console attach: server $serverId container is ${container.runState}, not RUNNING")
                return null
            }

            log.info("Attaching console to container ${container.containerName} (server=$serverId)")
            return DockerConsoleSession(container.containerName, containerManager)
        }
    }

    companion object {
        internal const val INPUT_QUEUE_CAPACITY = 256
    }
}
