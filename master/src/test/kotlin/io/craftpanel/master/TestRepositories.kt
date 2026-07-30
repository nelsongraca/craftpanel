package io.craftpanel.master

import io.craftpanel.master.service.repo.*
import io.craftpanel.master.service.repo.impl.*

class TestRepositories {

    val envVarsRepository = EnvVarsRepositoryImpl()
    val modRepository = ModRepositoryImpl()
    val migrationRepository = MigrationRepositoryImpl()
    val portRepository = PortRepositoryImpl()
    val backupRepository = BackupRepositoryImpl()
    val proxyBackendRepository = ProxyBackendRepositoryImpl()
    val containerMetricsRepository = ContainerMetricsRepositoryImpl()
    val serverJobRepository = ServerJobRepositoryImpl()
    val serverRepository = ServerRepositoryImpl()
}
