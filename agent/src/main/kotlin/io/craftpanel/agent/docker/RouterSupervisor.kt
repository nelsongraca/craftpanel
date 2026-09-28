package io.craftpanel.agent.docker

import io.craftpanel.agent.runtime.OutboundSink
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

class RouterSupervisor(
    private val provisioner: McRouterProvisioner,
    private val networkManager: NetworkManager,
    private val enabled: Boolean = true,
    private val out: OutboundSink? = null
) {

    private val log = LoggerFactory.getLogger(RouterSupervisor::class.java)
    private val _isRunning = AtomicBoolean(false)
    private val _updateAvailable = AtomicBoolean(false)

    // Last values pushed to master, so [publishStatus] only emits on a real change.
    @Volatile
    private var lastRunning = false

    @Volatile
    private var lastUpdateAvailable = false

    // Serializes ensureRunning() between the periodic loop and on-demand ensureReady() calls
    // (server start), so two concurrent provisioner runs cannot race on remove/create.
    private val provisionMutex = Mutex()

    // Reconcile the router's per-server network attachments once per agent lifetime (a router that
    // survived the agent restart keeps them, but a missed/failed attach must self-heal), and again
    // whenever ensureRunning reports a created/recreated/reused router. Guarded by [provisionMutex].
    private var reconciledAttachments = false

    val isRunning: Boolean get() = _isRunning.get()

    /** True when a pre-pulled newer router image awaits the explicit planned-downtime recreate. */
    val updateAvailable: Boolean get() = enabled && _updateAvailable.get()

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
        val provisioned = provisioner.ensureRunning()
        if (provisioned || !reconciledAttachments) {
            networkManager.reconcileRouterAttachments()
            reconciledAttachments = true
        }
        _isRunning.set(true)
        _updateAvailable.set(provisioner.updateAvailable)
        publishStatus()
        log.debug("mc-router running (updateAvailable={})", updateAvailable)
    }.onFailure { e ->
        _isRunning.set(false)
        publishStatus()
        log.warn("mc-router provisioning failed: ${e.message}")
    }.isSuccess

    /**
     * Explicit operator action (node page): recreate the router now, at a chosen downtime, applying
     * a pre-pulled newer image. Serialized with the periodic loop so it cannot race create/remove.
     */
    suspend fun recreateNow() {
        if (!enabled) return
        provisionMutex.withLock {
            runCatching {
                provisioner.recreateNow()
                networkManager.reconcileRouterAttachments()
                reconciledAttachments = true
                _isRunning.set(true)
                _updateAvailable.set(provisioner.updateAvailable)
            }.onFailure { e ->
                _isRunning.set(false)
                log.warn("Explicit mc-router recreate failed: ${e.message}")
            }
            publishStatus()
        }
    }

    /** Emits the router status only when it changed, so the node-page signal tracks live state. */
    private fun publishStatus() {
        val running = _isRunning.get()
        val update = _updateAvailable.get()
        if (running == lastRunning && update == lastUpdateAvailable) return
        lastRunning = running
        lastUpdateAvailable = update
        out?.tryRouterStatus(running, update)
    }

    companion object {

        private val HEALTHY_INTERVAL = 60.seconds
    }
}
