package io.craftpanel.agent.grpc.handlers

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import io.craftpanel.agent.docker.ContainerManager
import kotlinx.coroutines.CompletableDeferred
import org.slf4j.LoggerFactory

class DockerLogFetcher(private val containerManager: ContainerManager) : LogFetcher {

    private val log = LoggerFactory.getLogger(DockerLogFetcher::class.java)

    override suspend fun fetchLogs(serverId: String, tailLines: Int): List<String>? {
        val containers = containerManager.listContainers()
        val container = containers.find { it.serverId == serverId }
        if (container == null) {
            log.warn("FetchContainerLogs: server $serverId not found")
            return null
        }

        val lines = mutableListOf<String>()
        val latch = CompletableDeferred<Unit>()

        containerManager.fetchLogs(
            container.containerName,
            tailLines,
            object : ResultCallback.Adapter<Frame>() {
                override fun onNext(frame: Frame) {
                    frame.payload?.let { lines.add(String(it)) }
                }

                override fun onComplete() { latch.complete(Unit) }

                override fun onError(t: Throwable) { latch.completeExceptionally(t) }
            }
        )

        runCatching { latch.await() }
        return lines
    }
}
