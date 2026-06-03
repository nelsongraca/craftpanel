package io.craftpanel.agent

import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.DockerClientFactory
import io.craftpanel.agent.docker.McRouterProvisioner
import io.craftpanel.agent.docker.MetricsCollector
import io.craftpanel.agent.grpc.ConnectionManager
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

fun main(): Unit = runBlocking {
    val log = LoggerFactory.getLogger("io.craftpanel.agent.Main")
    log.info("CraftPanel Agent starting")

    val config = AgentConfig.fromEnv()
    config.validate()
    log.info("Master: ${config.masterAddress}:${config.masterPort} | TLS: ${config.tlsConfigured} | profile: ${config.profile} | dataPath: ${config.dataBasePath} | hostDataPath: ${config.hostDataBasePath}")

    val docker = DockerClientFactory.create(config.dockerSocketPath)
    val dockerSocket = config.dockerSocketPath.removePrefix("unix://")
    val containerManager = ContainerManager(docker, dockerSocket)
    val metricsCollector = MetricsCollector(docker)

    McRouterProvisioner(docker, config.mcRouterImage, config.mcRouterUpdateOnStart).ensureRunning()

    ConnectionManager(config, containerManager, metricsCollector, docker).run()
}
