package io.craftpanel.master

import io.craftpanel.master.service.ContainerLifecycle
import io.craftpanel.master.service.ModService
import io.craftpanel.master.service.ServerHostnames
import io.craftpanel.master.service.ServerIntent
import io.craftpanel.master.service.ServerSpecSync
import io.craftpanel.master.service.SettingsProvider
import io.craftpanel.master.service.repo.impl.SettingsRepositoryImpl

/**
 * A fully-wired [ServerSpecSync] backed by the test repos, so [ServerSpecSync.reconcile] builds the
 * real runtime spec and detects spec changes in tests that exercise pending-restart behaviour.
 */
fun testServerSpecSync(repos: TestRepositories, gateway: TestAgentGateway = TestAgentGateway()): ServerSpecSync {
    val lifecycle = ContainerLifecycle(
        gateway = gateway,
        modService = ModService(modRepository = repos.modRepository, serverRepository = repos.serverRepository),
        serverIntent = ServerIntent(repos.serverRepository),
        envVarsRepository = repos.envVarsRepository,
        extraPortRepository = repos.extraPortRepository,
        serverHostnames = ServerHostnames(SettingsProvider(SettingsRepositoryImpl()), repos.serverRepository)
    )
    return ServerSpecSync(lifecycle, repos.serverRepository)
}
