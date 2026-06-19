package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Event
import org.slf4j.LoggerFactory
import java.io.Closeable

/**
 * Streams Docker `die` events for managed containers and reports each death immediately via the
 * watch callback (serverId). This gives master near-instant crash signal instead of waiting for
 * the periodic state snapshot. Master decides whether to restart; the agent only reports.
 */
class ContainerEventWatcher(
    private val docker: DockerClient,
) {

    private val log = LoggerFactory.getLogger(ContainerEventWatcher::class.java)

    /**
     * Opens the event stream and invokes [onContainerCrash] or [onContainerStopped] for managed
     * container deaths. [shouldReport] gates the callback: returns false for intentional
     * deaths (stop/remove/recreate) and for containers not owned by this agent (cross-node guard
     * when agents share a Docker daemon). Exit code 0 → [onContainerStopped]; non-zero → [onContainerCrash].
     * Returns a [Closeable] to stop the stream on disconnect.
     */
    fun watch(
        shouldReport: (serverId: String) -> Boolean,
        onContainerCrash: (serverId: String) -> Unit,
        onContainerStopped: (serverId: String) -> Unit = {},
    ): Closeable {
        val callback = object : ResultCallback.Adapter<Event>() {
            override fun onNext(event: Event) {
                val serverId = event.actor?.attributes?.get("craftpanel.server.id")
                    ?.takeIf { it.isNotEmpty() } ?: return
                if (!shouldReport(serverId)) {
                    log.debug("Container die event for server {} suppressed (intentional or not owned)", serverId)
                    return
                }
                val exitCode = event.actor?.attributes?.get("exitCode")
                    ?.toIntOrNull() ?: -1
                if (exitCode == 0) {
                    log.info("Container die event for server {} — graceful stop (exit 0), reporting stopped", serverId)
                    runCatching { onContainerStopped(serverId) }
                        .onFailure { log.warn("Failed to report container stop for {}: {}", serverId, it.message) }
                }
                else {
                    log.info("Container die event for server {} — crash (exit {}), reporting unhealthy", serverId, exitCode)
                    runCatching { onContainerCrash(serverId) }
                        .onFailure { log.warn("Failed to report container crash for {}: {}", serverId, it.message) }
                }
            }

            override fun onError(throwable: Throwable) {
                log.warn("Container event stream error — snapshot reconcile remains the backstop: {}", throwable.message)
            }
        }
        docker.eventsCmd()
            .withEventTypeFilter("container")
            .withEventFilter("die")
            .withLabelFilter("craftpanel.managed=true")
            .exec(callback)
        log.info("Container event watcher started (die events, managed containers)")
        return callback
    }
}
