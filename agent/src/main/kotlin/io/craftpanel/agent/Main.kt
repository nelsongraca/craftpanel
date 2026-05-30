package io.craftpanel.agent

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.DockerClientFactory
import io.craftpanel.agent.docker.McRouterProvisioner
import io.craftpanel.agent.docker.MetricsCollector
import io.craftpanel.agent.grpc.ConnectionManager
import io.craftpanel.agent.grpc.DataServiceAuthInterceptor
import io.craftpanel.agent.grpc.DataServiceImpl
import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.netty.GrpcSslContexts
import io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

fun main(): Unit = runBlocking {
    val log = LoggerFactory.getLogger("io.craftpanel.agent.Main")
    log.info("CraftPanel Agent starting")

    val config = AgentConfig.fromEnv()
    config.validate()
    log.info("Master: ${config.masterAddress}:${config.masterPort} | TLS: ${config.tlsEnabled} | profile: ${config.profile}")

    val docker = DockerClientFactory.create(config.dockerSocketPath)
    val containerManager = ContainerManager(docker)
    val metricsCollector = MetricsCollector(docker)

    McRouterProvisioner(docker, config.mcRouterImage, config.mcRouterUpdateOnStart).ensureRunning()

    val dataService = DataServiceImpl(config, containerManager, docker)
    val dataServiceWithAuth = ServerInterceptors.intercept(dataService, DataServiceAuthInterceptor(config))

    val dataServerBuilder = NettyServerBuilder.forPort(config.dataServicePort)
        .addService(dataServiceWithAuth)

    if (config.dataServiceTlsEnabled) {
        dataServerBuilder.useTransportSecurity(
            File(config.dataServiceTlsCertPath),
            File(config.dataServiceTlsKeyPath),
        )
        log.info("DataService TLS enabled (cert: ${config.dataServiceTlsCertPath})")
    }
    else {
        check(config.profile == "dev") {
            "DATA_SERVICE_TLS_CERT and DATA_SERVICE_TLS_KEY are required outside dev profile"
        }
        log.error("DataService plaintext mode — DEV ONLY, not suitable for production")
    }

    val dataServer: Server = dataServerBuilder.build()
        .start()
    log.info("DataService gRPC server started on port ${config.dataServicePort}")

    Runtime.getRuntime()
        .addShutdownHook(Thread {
            dataServer.shutdown()
            log.info("DataService gRPC server stopped")
        })

    ConnectionManager(config, containerManager, metricsCollector).run()
}
