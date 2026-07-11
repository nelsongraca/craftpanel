package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

/**
 * Shared mutable state for all fake repositories. Per-domain fakes
 * are backed by the same maps so cross-repo queries (e.g. cascade
 * delete, findActiveMigration) work consistently.
 */
class FakeRepositories {

    val servers = mutableMapOf<Uuid, FakeServerRepository.MutableServer>()
    val envVars = mutableMapOf<Uuid, MutableList<EnvVarRow>>()
    val mods = mutableMapOf<Uuid, MutableMap<Uuid, FakeServerRepository.MutableMod>>()
    val migrations = mutableMapOf<Uuid, FakeServerRepository.MutableMigration>()
    val steps = mutableMapOf<Uuid, MutableList<FakeServerRepository.MutableMigrationStep>>()
    val ports = mutableListOf<FakeServerRepository.MutablePort>()
    val backups = mutableMapOf<Uuid, FakeServerRepository.MutableBackup>()
    val proxyBackends = mutableMapOf<Uuid, MutableList<FakeServerRepository.MutableProxyBackend>>()
    val containerMetrics = mutableListOf<FakeServerRepository.MutableContainerMetrics>()
    val serverJobs = mutableMapOf<Uuid, FakeServerRepository.MutableServerJob>()

    val serverRepository = FakeServerRepository(this)
    val envVarsRepository = FakeEnvVarsRepository(this)
    val modRepository = FakeModRepository(this)
    val migrationRepository = FakeMigrationRepository(this)
    val portRepository = FakePortRepository(this)
    val backupRepository = FakeBackupRepository(this)
    val proxyBackendRepository = FakeProxyBackendRepository(this)
    val containerMetricsRepository = FakeContainerMetricsRepository(this)
    val serverJobRepository = FakeServerJobRepository(this)
}
