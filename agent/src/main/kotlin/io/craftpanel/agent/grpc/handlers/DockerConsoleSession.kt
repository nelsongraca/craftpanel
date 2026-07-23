package io.craftpanel.agent.grpc.handlers

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import io.craftpanel.agent.docker.ContainerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.slf4j.LoggerFactory
import java.io.PipedInputStream
import java.io.PipedOutputStream

class DockerConsoleSession(
    containerName: String,
    containerManager: ContainerManager,
) : ConsoleSession {

    private val pipeOut = PipedOutputStream()
    private val pipeIn = PipedInputStream(pipeOut)
    private val _output = Channel<ByteArray>(BUFFERED)
    private val callback = object : ResultCallback.Adapter<Frame>() {
        override fun onNext(frame: Frame) {
            frame.payload?.takeIf { it.isNotEmpty() }?.let { _output.trySend(it) }
        }

        override fun onComplete() { _output.close() }

        override fun onError(t: Throwable) { _output.close(t) }
    }

    override val output: Flow<ByteArray> = _output.receiveAsFlow()

    init {
        containerManager.attachInteractive(containerName, pipeIn, callback)
    }

    override fun writeInput(data: ByteArray) {
        pipeOut.write(data)
        pipeOut.flush()
    }

    override fun close() {
        callback.close()
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
}
