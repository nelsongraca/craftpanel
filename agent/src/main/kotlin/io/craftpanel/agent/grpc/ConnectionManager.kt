package io.craftpanel.agent.grpc

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.MetricsCollector
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
            runCatching {
                log.info("Connecting to master at ${config.masterAddress}:${config.masterPort}")
                val channel = GrpcChannelFactory.create(config)

                try {
                    val identity = NodeAuthenticator(config).authenticate(channel)
                    backoffSeconds = 5L  // reset on successful auth

                    ControlStreamHandler(identity, containerManager, metricsCollector).run(channel)
                } finally {
                    channel.shutdown()
                }
            }.onFailure { e ->
                log.error("Connection failed: ${e.message} — reconnecting in ${backoffSeconds}s", e)
            }

            delay(backoffSeconds.seconds)
            backoffSeconds = min(backoffSeconds * 2, 120L)
        }
    }
}
