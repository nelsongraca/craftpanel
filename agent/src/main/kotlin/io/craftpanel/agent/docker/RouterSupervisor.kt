package io.craftpanel.agent.docker

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

class RouterSupervisor(private val provisioner: McRouterProvisioner, private val enabled: Boolean = true) {

    private val log = LoggerFactory.getLogger(RouterSupervisor::class.java)
    private val _isRunning = AtomicBoolean(false)

    // Serializes ensureRunning() between the periodic loop and on-demand ensureReady() calls
    // (server start), so two concurrent provisioner runs cannot race on remove/create.
    private val provisionMutex = Mutex()

    val isRunning: Boolean get() = _isRunning.get()

    suspend fun run() {
        if (!enabled) {
            log.info("mc-router management disabled (MCROUTER_ENABLED=false)")
            _isRunning.set(true)
            return
        }
        var backoffSeconds = 5L
        while (true) {
            val ok = provisionMutex.withLock { provision() }

            // Healthy: re-check at a slow heartbeat instead of hammering ensureRunning every 5s
            // (each call inspects + re-connects to network → repeated 403 noise on a busy daemon).
            // Failing: keep the 5s→120s exponential backoff.
            if (ok) {
                delay(HEALTHY_INTERVAL)
                backoffSeconds = 5L
            } else {
                delay(backoffSeconds.seconds)
                backoffSeconds = min(backoffSeconds * 2, 120L)
            }
        }
    }

    /**
     * Runs [McRouterProvisioner.ensureRunning] now and waits for it, so a server start can depend on
     * mc-router being present and correct. No-op when management is disabled. Never throws — a
     * failure marks the router not-running and is reported by the periodic loop; the caller decides
     * whether a missing router should fail the start.
     */
    suspend fun ensureReady() {
        if (!enabled) return
        provisionMutex.withLock { provision() }
    }

    /** Returns true when mc-router is up and correct after the call. Caller must hold [provisionMutex]. */
    private fun provision(): Boolean = runCatching {
        provisioner.ensureRunning()
        _isRunning.set(true)
        log.debug("mc-router running")
    }.onFailure { e ->
        _isRunning.set(false)
        log.warn("mc-router provisioning failed: ${e.message}")
    }.isSuccess

    companion object {

        private val HEALTHY_INTERVAL = 60.seconds
    }
}
