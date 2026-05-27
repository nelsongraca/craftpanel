package io.craftpanel.agent

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.DockerClientFactory
import io.craftpanel.agent.docker.MetricsCollector
import io.craftpanel.agent.grpc.ConnectionManager
import io.craftpanel.agent.grpc.DataServiceImpl
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

fun main(): Unit = runBlocking {
    val log = LoggerFactory.getLogger("io.craftpanel.agent.Main")
    log.info("CraftPanel Agent starting")

    val config = AgentConfig.fromEnv()
    log.info("Master: ${config.masterAddress}:${config.masterPort} | TLS: ${config.tlsEnabled}")

    val docker = DockerClientFactory.create(config.dockerSocketPath)
    val containerManager = ContainerManager(docker)
    val metricsCollector = MetricsCollector(docker)

    val dataService = DataServiceImpl(config, containerManager, docker)
    val dataServer: Server = NettyServerBuilder.forPort(config.dataServicePort)
        .addService(dataService)
        .build()
        .start()
    log.info("DataService gRPC server started on port ${config.dataServicePort}")

    Runtime.getRuntime().addShutdownHook(Thread {
        dataServer.shutdown()
        log.info("DataService gRPC server stopped")
    })

    ConnectionManager(config, containerManager, metricsCollector).run()
}
