package io.craftpanel.agent.di

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.*
import io.craftpanel.agent.grpc.handlers.*
import org.koin.dsl.module
import java.time.Duration

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
            get<AgentConfig>().pullMaxImageAgeHours
        )
    }
    single { MetricsCollector(get<DockerClient>(), get<AgentConfig>().craftpanelNetwork) }
    single {
        RsyncMigrator(
            get<DockerClient>(),
            get<AgentConfig>().craftpanelNetwork,
            get<AgentConfig>().containerNamePrefix
        )
    }
    scope<ConnectionScope> {
        scoped { ConsoleHandler(get()) }
        scoped { (nodeKey: String) -> FileHandler(get(), nodeKey) }
        scoped { (networkManager: NetworkManager) -> ContainerHandler(get(), get(), networkManager) }
        scoped { BackupHandler(get()) }
        scoped { MigrationHandler(get(), get(), get()) }
    }
}
