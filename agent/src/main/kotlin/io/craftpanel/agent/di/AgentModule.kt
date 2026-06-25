package io.craftpanel.agent.di

import com.github.dockerjava.api.DockerClient
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.NetworkManager
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import java.time.Duration
import io.craftpanel.agent.docker.MetricsCollector
import io.craftpanel.agent.grpc.handlers.BackupHandler
import io.craftpanel.agent.grpc.handlers.ConsoleHandler
import io.craftpanel.agent.grpc.handlers.ContainerHandler
import io.craftpanel.agent.grpc.handlers.FileHandler
import io.craftpanel.agent.grpc.handlers.MigrationHandler
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

class ConnectionScope

private fun createDockerClient(socketPath: String): DockerClient {
    val config = DefaultDockerClientConfig.createDefaultConfigBuilder()
        .withDockerHost(socketPath)
        .build()

    val httpClient = ApacheDockerHttpClient.Builder()
        .dockerHost(config.dockerHost)
        .sslConfig(config.sslConfig)
        .maxConnections(100)
        .connectionTimeout(Duration.ofSeconds(30))
        .responseTimeout(Duration.ofSeconds(45))
        .build()

    return DockerClientImpl.getInstance(config, httpClient)
}

val agentModule = module {
    single {
        AgentConfig.fromEnv()
            .also { it.validate() }
    }
    single { createDockerClient(get<AgentConfig>().dockerSocketPath) }
    single {
        ContainerManager(
            get<DockerClient>(),
            get<AgentConfig>().craftpanelNetwork,
            get<AgentConfig>().containerNamePrefix,
            get<AgentConfig>().pullMaxImageAgeHours,
        )
    }
    single { MetricsCollector(get<DockerClient>(), get<AgentConfig>().craftpanelNetwork) }
    single { NetworkManager(get<DockerClient>(), "${get<AgentConfig>().containerNamePrefix}-mc-router") }

    scope<ConnectionScope> {
        scoped { ConsoleHandler(get(), get()) }
        scoped { (nodeKey: String) -> FileHandler(get(), nodeKey) }
        scoped { ContainerHandler(get(), get(), get()) }
        scoped { BackupHandler(get()) }
        scoped { MigrationHandler(get(), get()) }
    }
}
