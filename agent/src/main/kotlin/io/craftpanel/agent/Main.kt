package io.craftpanel.agent

import com.github.dockerjava.api.DockerClient
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.di.agentModule
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.MetricsCollector
import io.craftpanel.agent.grpc.ConnectionManager
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.slf4j.LoggerFactory

fun main(): Unit = runBlocking {
    val log = LoggerFactory.getLogger("io.craftpanel.agent.Main")
    log.info("CraftPanel Agent starting")

    val koin = startKoin { modules(agentModule) }.koin
    val config = koin.get<AgentConfig>()
    log.info("Master: ${config.masterAddress}:${config.masterPort} | TLS: ${config.tlsConfigured} | profile: ${config.profile} | dataPath: ${config.dataBasePath} | hostDataPath: ${config.hostDataBasePath}")

    val docker = koin.get<DockerClient>()

    check(
        docker.listNetworksCmd()
            .withNameFilter(config.craftpanelNetwork)
            .exec()
            .any { it.name == config.craftpanelNetwork }) {
        "Docker network '${config.craftpanelNetwork}' not found — create it: docker network create ${config.craftpanelNetwork}"
    }
    val ownHostname = System.getenv("HOSTNAME") ?: ""
    if (ownHostname.isNotEmpty()) {
        runCatching {
            val ownNetworks = docker.inspectContainerCmd(ownHostname)
                .exec()
                .networkSettings?.networks?.keys.orEmpty()
            if (config.craftpanelNetwork !in ownNetworks) {
                log.warn(
                    "Agent container is not attached to network '${config.craftpanelNetwork}' — " +
                            "add it to the Compose networks section. " +
                            "mc-router and game server containers will not be reachable."
                )
            }
        }.onFailure {
            log.debug("Could not inspect own container for network check: ${it.message}")
        }
    }
    log.info("Docker network: ${config.craftpanelNetwork}")

    val containerManager = koin.get<ContainerManager>()
    val metricsCollector = koin.get<MetricsCollector>()

    ConnectionManager(koin, config, containerManager, metricsCollector, docker).run(this)
}
