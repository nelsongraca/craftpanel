package io.craftpanel.agent.grpc

import io.craftpanel.agent.auth.NodeKeyStore
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.di.ConnectionScope
import io.craftpanel.agent.di.REALTIME_LANE
import io.craftpanel.agent.di.TELEMETRY_LANE
import io.craftpanel.proto.AgentMessage
import io.grpc.ManagedChannel
import io.grpc.netty.GrpcSslContexts
import io.grpc.netty.NettyChannelBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.min
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

class ConnectionManager(
    private val koin: Koin,
    private val config: AgentConfig
) {

    private val log = LoggerFactory.getLogger(ConnectionManager::class.java)

    private fun createChannel(): ManagedChannel {
        val builder = NettyChannelBuilder.forAddress(config.masterAddress, config.masterPort)

        val certPem: String? = when {
            config.tlsEnabled -> File(config.tlsCertPath).readText()
            else -> NodeKeyStore.read(config.caCertFilePath)
        }

        if (certPem != null) {
            val sslContext = GrpcSslContexts.forClient()
                .trustManager(ByteArrayInputStream(certPem.toByteArray()))
                .build()
            builder.sslContext(sslContext)
        } else {
            check(config.profile == "dev") {
                "gRPC TLS is required outside dev profile — set GRPC_TLS_CERT or mount master's grpc-ca.crt at ${config.caCertFilePath}"
            }
            builder.usePlaintext()
        }

        return builder.build()
    }

    suspend fun run(coroutineScope: CoroutineScope) {
        var backoffSeconds = 5L

        while (true) {
            val result = runCatching {
                log.info("Connecting to master at ${config.masterAddress}:${config.masterPort}")
                val channel = createChannel()
                try {
                    // Identity authenticates over the channel before any per-connection object graph
                    // exists; the result is declared into the scope so every scoped service can inject it.
                    val identity = koin.get<NodeAuthenticator>().authenticate(channel)
                    backoffSeconds = 5L // reset on successful auth
                    Heartbeat.beat()

                    // Per-connection scope: built once authenticated, closed when the stream dies.
                    // `close()` cancels the convergence scope and releases every scoped instance.
                    val scope = koin.createScope("connection-${System.nanoTime()}", named<ConnectionScope>())
                    try {
                        scope.declare(channel)
                        scope.declare(identity)
                        val handler = scope.get<ControlStreamHandler>()
                        val realtime = scope.get<Channel<AgentMessage>>(named(REALTIME_LANE))
                        val telemetry = scope.get<Channel<AgentMessage>>(named(TELEMETRY_LANE))
                        handler.run(channel, realtime, telemetry)
                    } finally {
                        scope.close()
                    }
                } finally {
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
