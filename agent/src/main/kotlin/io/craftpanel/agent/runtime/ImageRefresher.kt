package io.craftpanel.agent.runtime

import io.craftpanel.agent.config.RuntimeSettingsStore
import io.craftpanel.agent.docker.ContainerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/**
 * Best-effort pre-pull of every image the node manages (server specs + mc-router), on the
 * install-wide `image_refresh_interval_seconds` cadence. Docker's pull is a no-op when a tag has
 * not moved, so the cost is a registry round trip; when it has moved, the newer image is already
 * local by the time it is applied at the next restart / explicit router recreate.
 *
 * Never downloads destructively and never restarts anything. A `docker system prune -a` on the host
 * can drop a pre-pulled image that no container references yet; that only delays readiness (an
 * apply re-pulls), and the "router update available" signal may lag until the next refresh.
 *
 * The loop runs regardless of connectivity (the runtime is process-scoped); it re-reads the cadence
 * on every tick, so an operator changing the setting takes effect live.
 */
class ImageRefresher(
    private val containerManager: ContainerManager,
    private val managedImages: () -> Set<String>,
    private val routerImage: () -> String,
    private val settingsStore: RuntimeSettingsStore
) {

    private val log = LoggerFactory.getLogger(ImageRefresher::class.java)

    suspend fun run() {
        while (true) {
            val interval = settingsStore.current().imageRefreshIntervalSeconds
            if (interval <= 0) {
                // Re-check periodically so re-enabling through settings takes effect.
                delay(DISABLED_RECHECK)
                continue
            }
            refreshAll()
            delay(interval.seconds)
        }
    }

    internal suspend fun refreshAll() {
        val images = (managedImages() + routerImage())
            .filter { it.isNotBlank() }
            .distinct()
        if (images.isEmpty()) return
        for (image in images) {
            withContext(Dispatchers.IO) {
                runCatching { containerManager.refreshImage(image) }
                    .onFailure { log.warn("Image refresh failed for $image", it) }
            }
        }
    }

    private companion object {

        val DISABLED_RECHECK = 60.seconds
    }
}
