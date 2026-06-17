package io.craftpanel.agent.grpc

import com.github.dockerjava.api.DockerClient
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.di.ConnectionScope
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.MetricsCollector
import io.craftpanel.agent.docker.RouterSupervisor
import kotlinx.coroutines.delay
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.slf4j.LoggerFactory
import kotlin.math.min
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

class ConnectionManager(
    private val koin: Koin,
    private val config: AgentConfig,
    private val containerManager: ContainerManager,
    private val metricsCollector: MetricsCollector,
    private val docker: DockerClient,
    private val routerSupervisor: RouterSupervisor,
) {

    private val log = LoggerFactory.getLogger(ConnectionManager::class.java)

    suspend fun run() {
        var backoffSeconds = 5L

        while (true) {
            val result = runCatching {
                log.info("Connecting to master at ${config.masterAddress}:${config.masterPort}")
                val channel = GrpcChannelFactory.create(config)

                val scope = koin.createScope(
                    scopeId = "connection-" + System.nanoTime(),
                    qualifier = named<ConnectionScope>(),
                )
                try {
                    val identity = NodeAuthenticator(config, metricsCollector).authenticate(channel)
                    backoffSeconds = 5L  // reset on successful auth

                    ControlStreamHandler(
                        identity, config, containerManager, metricsCollector, docker,
                        routerSupervisor = routerSupervisor,
                        container = scope.get(),
                        backup = scope.get(),
                        migration = scope.get(),
                        file = scope.get { parametersOf(identity.nodeKey) },
                        console = scope.get(),
                    ).run(channel)
                }
                finally {
                    scope.close()
                    channel.shutdown()
                }
            }

            result.exceptionOrNull()
                ?.let { e ->
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
