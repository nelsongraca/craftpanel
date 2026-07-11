package io.craftpanel.master.service.migration

import io.craftpanel.master.TestAgentGateway
import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.master.service.ContainerLifecycle
import io.craftpanel.master.service.ModService
import io.craftpanel.master.service.ServerExposure
import io.craftpanel.master.service.migration.steps.InitialRsyncStep
import io.craftpanel.master.service.repo.NetworkRepositoryImpl
import io.craftpanel.master.service.repo.NodeRepositoryImpl
import io.craftpanel.master.service.repo.NodeRow
import io.craftpanel.master.service.repo.ServerRow
import io.craftpanel.master.service.repo.SettingsRepositoryImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

class InitialRsyncStepTest :
    FunSpec({
        lateinit var events: MutableSharedFlow<AgentEvent>
        lateinit var gateway: TestAgentGateway

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
            events = MutableSharedFlow(extraBufferCapacity = 16)
            gateway = TestAgentGateway(agentEvents = events)
        }

        fun plan() = MigrationPlan(
            migrationId = Uuid.random(),
            migrationIdStr = "mig-1",
            serverId = Uuid.random(),
            serverIdStr = "srv-1",
            sourceNodeId = Uuid.random(),
            sourceNodeIdStr = "src-1",
            targetNodeId = Uuid.random(),
            targetNodeIdStr = "tgt-1",
            rsyncImage = "alpine",
            playerWarningMessage = "Server restarting",
            containerNamePrefix = "craftpanel",
            serverRow = ServerRow(
                id = Uuid.random(), name = "test", displayName = "test",
                description = null, nodeId = Uuid.random(), networkId = null,
                serverType = "VANILLA", mcVersion = "1.21.4", status = "RUNNING",
                hostPort = 25565, memoryMb = 1024, cpuShares = 0,
                exposedExternally = false, publicSubdomain = null,
                dnsRecordId = null, dnsRecordName = null, customHostname = null,
                configMode = "MANAGED", stopCommand = "stop", itzgImageTag = "latest",
                needsRecreate = false, backupSchedule = null, backupMaxCount = 0,
                backupScheduleLastFired = null, lastPlayerCount = null,
                lastPlayerNames = null, lastPlayerUpdate = null, lastSeenAt = null,
                createdAt = "", updatedAt = ""
            ),
            targetNodeRow = NodeRow(
                id = Uuid.random(), displayName = "target", hostname = "target",
                publicIp = "1.2.3.4", privateIp = "10.0.0.1", tokenHash = "",
                status = "ACTIVE", health = "HEALTHY", totalRamMb = 8192, totalCpuShares = 0,
                systemRamUsedMb = null, portRangeStart = 25565, portRangeEnd = 25600,
                swarmActive = false, agentVersion = null, lastSeenAt = null,
                createdAt = "", updatedAt = ""
            ),
            targetPrivateIp = "10.0.0.1"
        ).apply {
            rsyncPort = 25566
            rsyncPassword = "secret"
        }

        fun coord(scope: TestScope, gw: TestAgentGateway = gateway): MigrationCoordinator {
            val repos = TestRepositories()
            return MigrationCoordinator(
                migrationRepository = repos.migrationRepository,
                serverRepository = repos.serverRepository,
                portRepository = repos.portRepository,
                proxyBackendRepository = repos.proxyBackendRepository,
                nodeRepository = NodeRepositoryImpl(),
                gateway = gw,
                dnsProvider = null,
                lifecycle = ContainerLifecycle(
                    gateway = TestAgentGateway(),
                    modService = ModService(modRepository = repos.modRepository, serverRepository = repos.serverRepository),
                    serverRepository = repos.serverRepository,
                    envVarsRepository = repos.envVarsRepository
                ),
                serverExposure = ServerExposure(NetworkRepositoryImpl(), SettingsRepositoryImpl(), repos.serverRepository),
                scope = scope,
                eventFlow = null
            )
        }

        test("Success when rsync completes within timeout") {
            runTest {
                val p = plan()
                val c = coord(this)
                launch {
                    delay(50.milliseconds)
                    events.emit(AgentEvent.RsyncCompleteEvent("mig-1", false, true, ""))
                }
                val result = InitialRsyncStep().execute(p, c)
                result.shouldBeInstanceOf<StepResult.Success>()
            }
        }

        test("Failure when sendToNode returns false") {
            runTest {
                val p = plan()
                val badGateway = TestAgentGateway(agentEvents = events, sendResult = false)
                val c = coord(this, badGateway)
                val result = InitialRsyncStep().execute(p, c)
                result.shouldBeInstanceOf<StepResult.Failure>()
            }
        }

        test("Failure when complete.success is false") {
            runTest {
                val p = plan()
                val c = coord(this)
                launch {
                    delay(50.milliseconds)
                    events.emit(AgentEvent.RsyncCompleteEvent("mig-1", false, false, "rsync error"))
                }
                val result = InitialRsyncStep().execute(p, c)
                result.shouldBeInstanceOf<StepResult.Failure>()
            }
        }

        test("Failure on timeout") {
            runTest {
                val p = plan()
                val c = coord(this)
                val result = InitialRsyncStep().execute(p, c)
                result.shouldBeInstanceOf<StepResult.Failure>()
            }
        }
    })
