package io.craftpanel.agent.grpc

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.MetricsCollector
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

class ConnectionManager(
    private val config: AgentConfig,
    private val containerManager: ContainerManager,
    private val metricsCollector: MetricsCollector,
) {
    private val log = LoggerFactory.getLogger(ConnectionManager::class.java)

    suspend fun run() {
        var backoffSeconds = 5L

        while (true) {
            val result = runCatching {
                log.info("Connecting to master at ${config.masterAddress}:${config.masterPort}")
                val channel = GrpcChannelFactory.create(config)

                try {
                    val identity = NodeAuthenticator(config, metricsCollector).authenticate(channel)
                    backoffSeconds = 5L  // reset on successful auth

                    ControlStreamHandler(identity, containerManager, metricsCollector).run(channel)
                } finally {
                    channel.shutdown()
                }
            }

            result.exceptionOrNull()?.let { e ->
                if (e is NodeRejectedException) {
                    log.error("Node REJECTED by master — halting permanently")
                    exitProcess(1)
                }
                log.error("Connection failed: ${e.message} — reconnecting in ${backoffSeconds}s", e)
            }

            delay(backoffSeconds.seconds)
            backoffSeconds = min(backoffSeconds * 2, 120L)
        }
    }
}
