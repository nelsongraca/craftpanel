package io.craftpanel.agent.docker

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

class RouterSupervisor(private val provisioner: McRouterProvisioner) {

    private val log = LoggerFactory.getLogger(RouterSupervisor::class.java)
    private val _isRunning = AtomicBoolean(false)

    val isRunning: Boolean get() = _isRunning.get()

    suspend fun run() {
        var backoffSeconds = 5L
        while (true) {
            val ok = runCatching {
                provisioner.ensureRunning()
                _isRunning.set(true)
                log.info("mc-router running")
            }.onFailure { e ->
                _isRunning.set(false)
                log.warn("mc-router provisioning failed — retrying in ${backoffSeconds}s: ${e.message}")
            }.isSuccess

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

    companion object {

        private val HEALTHY_INTERVAL = 60.seconds
    }
}
