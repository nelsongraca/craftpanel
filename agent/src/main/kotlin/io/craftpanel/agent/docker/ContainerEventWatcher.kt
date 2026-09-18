package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Event
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Streams Docker `die` events for managed containers and reports each death via the watch callback
 * (serverId). This gives master near-instant crash signal instead of waiting for the periodic state
 * snapshot. Master decides whether to restart; the agent only reports.
 *
 * The stream is self-healing: if it completes or errors (the common case after a Docker daemon
 * restart / host reboot), it is re-subscribed with exponential backoff. Without this, a dropped
 * event stream would silently disable crash detection for the rest of the agent process's life.
 * The periodic reconcile sweep in the convergence loop is the second backstop.
 */
class ContainerEventWatcher(
    private val docker: DockerClient,
    /** Initial resubscribe delay; injectable so tests run fast. */
    private val initialBackoffMs: Long = 1_000L,
    /** Maximum resubscribe delay. */
    private val maxBackoffMs: Long = 60_000L
) {

    private val log = LoggerFactory.getLogger(ContainerEventWatcher::class.java)

    /**
     * Opens the event stream and invokes [onContainerCrash] or [onContainerStopped] for managed
     * container deaths. [shouldReport] gates the callback: returns false for intentional
     * deaths (stop/remove/recreate) and for containers not owned by this agent (cross-node guard
     * when agents share a Docker daemon). Exit code 0 → [onContainerStopped]; non-zero → [onContainerCrash].
     * Either way the convergence loop re-decides from master intent — exit code is informational.
     * Returns a [Closeable] that stops the stream and the resubscribe loop on disconnect.
     */
    fun watch(scope: CoroutineScope, shouldReport: (serverId: String) -> Boolean, onContainerCrash: (serverId: String) -> Unit, onContainerStopped: (serverId: String) -> Unit = {}): Closeable {
        val closed = AtomicBoolean(false)
        val current = AtomicReference<ResultCallback.Adapter<Event>?>(null)
        val job = scope.launch(Dispatchers.IO) {
            var backoffMs = initialBackoffMs
            while (isActive && !closed.get()) {
                val finished = CompletableDeferred<Unit>()
                val callback = object : ResultCallback.Adapter<Event>() {
                    override fun onStart(stream: Closeable) {
                        backoffMs = initialBackoffMs
                        log.info("Container event watcher subscribed (die events, managed containers)")
                    }

                    override fun onNext(event: Event) {
                        handle(event, shouldReport, onContainerCrash, onContainerStopped)
                    }

                    override fun onError(throwable: Throwable) {
                        log.warn("Container event stream error — resubscribing: {}", throwable.message)
                        finished.complete(Unit)
                    }

                    override fun onComplete() {
                        log.warn("Container event stream completed — resubscribing")
                        finished.complete(Unit)
                    }
                }
                current.set(callback)
                runCatching {
                    docker.eventsCmd()
                        .withEventTypeFilter("container")
                        .withEventFilter("die")
                        .withLabelFilter("craftpanel.managed=true")
                        .exec(callback)
                }.onFailure {
                    log.warn("Failed to subscribe to container events: {} — retrying", it.message)
                    finished.complete(Unit)
                }

                finished.await()
                if (closed.get() || !isActive) break
                log.info("Resubscribing to container events in {}ms", backoffMs)
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
            }
            log.info("Container event watcher stopped")
        }
        return Closeable {
            closed.set(true)
            runCatching { current.get()?.close() }
            job.cancel()
        }
    }

    private fun handle(event: Event, shouldReport: (serverId: String) -> Boolean, onContainerCrash: (serverId: String) -> Unit, onContainerStopped: (serverId: String) -> Unit) {
        val serverId = event.actor?.attributes?.get("craftpanel.server.id")
            ?.takeIf { it.isNotEmpty() } ?: return
        val containerName = event.actor?.attributes?.get("name") ?: "?"
        val exitCode = event.actor?.attributes?.get("exitCode")?.toIntOrNull() ?: -1
        if (!shouldReport(serverId)) {
            log.info(
                "Container die event for server {} (container={}, exit={}) suppressed — not managed or intentionally stopping",
                serverId,
                containerName,
                exitCode
            )
            return
        }
        if (exitCode == 0) {
            log.info("Container die event for server {} (container={}, exit 0) — reporting stopped", serverId, containerName)
            runCatching { onContainerStopped(serverId) }
                .onFailure { log.warn("Failed to report container stop for {}: {}", serverId, it.message) }
        } else {
            log.info("Container die event for server {} (container={}, exit {}) — reporting crash", serverId, containerName, exitCode)
            runCatching { onContainerCrash(serverId) }
                .onFailure { log.warn("Failed to report container crash for {}: {}", serverId, it.message) }
        }
    }
}
