package io.craftpanel.agent

import com.github.dockerjava.api.DockerClient
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.config.RuntimeSettingsStore
import io.craftpanel.agent.di.agentModule
import io.craftpanel.agent.docker.RouterSupervisor
import io.craftpanel.agent.grpc.ConnectionManager
import io.craftpanel.agent.runtime.AgentRuntime
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.slf4j.LoggerFactory

fun main(): Unit = runBlocking {
    val log = LoggerFactory.getLogger("io.craftpanel.agent.Main")
    log.info("CraftPanel Agent starting")

    val koin = startKoin { modules(agentModule) }.koin
    val config = koin.get<AgentConfig>()
    log.info(
        "Master: ${config.masterAddress}:${config.masterPort} | TLS: ${config.tlsConfigured} | profile: ${config.profile} | dataPath: ${config.dataBasePath} | hostDataPath: ${config.hostDataBasePath}"
    )

    val docker = koin.get<DockerClient>()

    check(
        docker.listNetworksCmd()
            .withNameFilter(config.craftpanelNetwork)
            .exec()
            .any { it.name == config.craftpanelNetwork }
    ) {
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

    // Last accepted runtime settings survive a master outage (and an agent restart during one).
    koin.get<RuntimeSettingsStore>()
        .load()

    // Process-scoped: the router supervisor is created once and reused across reconnects.
    launch {
        koin.get<RouterSupervisor>()
            .run()
    }

    // Convergence, reconcile sweep, metrics pump and the Docker event watcher outlive the control
    // stream, so crash-restart keeps working while master is unreachable.
    koin.get<AgentRuntime>()
        .start()

    ConnectionManager(koin, config).run(this)
}
