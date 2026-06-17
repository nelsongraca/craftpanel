package io.craftpanel.agent.docker

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

class RouterSupervisor(
    private val provisioner: McRouterProvisioner,
) {

    private val log = LoggerFactory.getLogger(RouterSupervisor::class.java)
    private val _running = AtomicBoolean(false)

    val isRunning: Boolean get() = _running.get()

    suspend fun run() {
        var backoffSeconds = 5L
        while (true) {
            val result = runCatching {
                provisioner.ensureRunning()
                _running.set(true)
                backoffSeconds = 5L
                log.info("mc-router running")
            }
            result.onFailure { e ->
                _running.set(false)
                log.warn("mc-router provisioning failed — retrying in ${backoffSeconds}s: ${e.message}")
            }
            delay(backoffSeconds.seconds)
            backoffSeconds = min(backoffSeconds * 2, 120L)
        }
    }
}
