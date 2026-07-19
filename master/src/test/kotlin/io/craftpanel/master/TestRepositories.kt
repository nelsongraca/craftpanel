package io.craftpanel.master

import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*

/**
 * Wires up all real (H2-backed) repository implementations for tests.
 * ServerRepositoryImpl gets all 8 sub-repos from this object.
 */
class TestRepositories {

    val envVarsRepository = EnvVarsRepositoryImpl()
    val modRepository = ModRepositoryImpl()
    val migrationRepository = MigrationRepositoryImpl()
    val portRepository = PortRepositoryImpl()
    val backupRepository = BackupRepositoryImpl()
    val proxyBackendRepository = ProxyBackendRepositoryImpl()
    val containerMetricsRepository = ContainerMetricsRepositoryImpl()
    val serverJobRepository = ServerJobRepositoryImpl()
    val serverRepository = ServerRepositoryImpl(
        envVarsRepository = envVarsRepository,
        modRepository = modRepository,
        migrationRepository = migrationRepository,
        portRepository = portRepository,
        backupRepository = backupRepository,
        proxyBackendRepository = proxyBackendRepository,
        containerMetricsRepository = containerMetricsRepository,
        serverJobRepository = serverJobRepository
    )
}
