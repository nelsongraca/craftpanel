package io.craftpanel.agent.di

import com.github.dockerjava.api.DockerClient
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.agent.docker.DockerClientFactory
import io.craftpanel.agent.docker.MetricsCollector
import io.craftpanel.agent.grpc.handlers.BackupHandler
import io.craftpanel.agent.grpc.handlers.ConsoleHandler
import io.craftpanel.agent.grpc.handlers.ContainerHandler
import io.craftpanel.agent.grpc.handlers.FileHandler
import io.craftpanel.agent.grpc.handlers.MigrationHandler
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

class ConnectionScope

val agentModule = module {
    single {
        AgentConfig.fromEnv()
            .also { it.validate() }
    }
    single { DockerClientFactory.create(get<AgentConfig>().dockerSocketPath) }
    single { ContainerManager(get<DockerClient>(), get<AgentConfig>().craftpanelNetwork, get<AgentConfig>().containerNamePrefix) }
    single { MetricsCollector(get<DockerClient>(), get<AgentConfig>().craftpanelNetwork) }

    scope<ConnectionScope> {
        scoped { ConsoleHandler(get(), get()) }
        scoped { (nodeKey: String) -> FileHandler(get(), nodeKey) }
        scoped { ContainerHandler(get(), get()) }
        scoped { BackupHandler(get()) }
        scoped { MigrationHandler(get(), get()) }
    }
}
