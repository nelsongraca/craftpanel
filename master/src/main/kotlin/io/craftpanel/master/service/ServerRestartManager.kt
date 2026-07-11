package io.craftpanel.master.service

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * In-memory crash-restart cap. Master owns container restart (Docker policy is "no"), so when a
 * managed container dies unexpectedly we restart it — but bounded, to avoid a crash loop hammering
 * the node. Counts are per-server, consecutive, and reset when the server next reaches HEALTHY or
 * when the window lapses. Intentionally not persisted: a master restart clears the counters, which
 * is acceptable for a crash-loop guard.
 */
class ServerRestartManager(private val maxAttempts: Int, private val windowSeconds: Long) {

    private val log = LoggerFactory.getLogger(ServerRestartManager::class.java)

    private data class Attempts(val count: Int, val firstAt: Instant)

    private val attempts = ConcurrentHashMap<Uuid, Attempts>()

    /**
     * Records a crash for [serverId] and returns true if a restart should be attempted (i.e. the
     * cap has not been exceeded). A gap longer than the window resets the counter to this crash.
     */
    fun recordCrashAndShouldRestart(serverId: Uuid): Boolean {
        if (maxAttempts <= 0) return false
        val now = Clock.System.now()
        val updated = attempts.compute(serverId) { _, prev ->
            if (prev == null || now - prev.firstAt > windowSeconds.seconds) {
                Attempts(count = 1, firstAt = now)
            } else {
                prev.copy(count = prev.count + 1)
            }
        }!!
        val allowed = updated.count <= maxAttempts
        if (allowed) {
            log.info("Crash restart for server {} — attempt {}/{}", serverId, updated.count, maxAttempts)
        } else {
            log.warn("Crash restart cap reached for server {} ({} in {}s) — leaving UNHEALTHY for manual action", serverId, updated.count - 1, windowSeconds)
        }
        return allowed
    }

    /** Clears the crash counter — call when the server reaches HEALTHY or is explicitly started/stopped. */
    fun reset(serverId: Uuid) {
        attempts.remove(serverId)
    }
}
