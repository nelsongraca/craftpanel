package io.craftpanel.master.service

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.database.entity.Backup
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.AgentEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class NodeObserverBackupTest :
    FunSpec({

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        test("backup completion persists the archive path so it can be purged later") {
            runBlocking {
                val nodeId = transaction {
                    Nodes.insert {
                        it[Nodes.hostname] = "node-1"
                        it[Nodes.displayName] = "node-1"
                        it[Nodes.publicIp] = "1.2.3.4"
                        it[Nodes.privateIp] = "10.0.0.1"
                        it[Nodes.tokenHash] = "a".repeat(64)
                        it[Nodes.status] = "ACTIVE"
                        it[Nodes.totalRamMb] = 8192
                        it[Nodes.totalCpuMillicores] = 1024
                    }[Nodes.id]
                }
                val serverId = transaction {
                    Servers.insert {
                        it[Servers.name] = "test-server"
                        it[Servers.displayName] = "Test Server"
                        it[Servers.nodeId] = nodeId
                        it[Servers.serverType] = "VANILLA"
                        it[Servers.mcVersion] = "LATEST"
                        it[Servers.memoryMb] = 1024
                        it[Servers.hostPort] = 25565
                    }[Servers.id]
                }
                val backupId = transaction {
                    Backup.new {
                        this.serverId = serverId
                        this.nodeId = nodeId
                        trigger = "MANUAL"
                        status = "IN_PROGRESS"
                    }.id.value
                }

                val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 4)
                val observer = NodeObserver(
                    agentEvents = events,
                    emitAgentEvent = {},
                    serverRepository = mockk(relaxed = true),
                    nodeRepository = mockk(relaxed = true),
                    containerMetricsRepository = mockk(relaxed = true),
                    backupRepository = mockk(relaxed = true),
                    alertEvaluator = mockk(relaxed = true)
                )
                val scope = CoroutineScope(Dispatchers.Default)
                observer.start(scope)
                try {
                    withTimeout(5.seconds) { events.subscriptionCount.first { it > 0 } }
                    val archivePath = "/data/backups/$backupId.tar.gz"
                    events.emit(
                        AgentEvent.BackupCompleteEvent(
                            serverId = serverId.toString(),
                            backupId = backupId.toString(),
                            success = true,
                            sizeBytes = 2048,
                            errorMessage = "",
                            completedAt = Clock.System.now(),
                            filePath = archivePath
                        )
                    )

                    withTimeout(5.seconds) {
                        var storedPath: String? = null
                        while (storedPath == null) {
                            delay(20)
                            storedPath = transaction { Backup.findById(backupId)?.filePath }
                        }
                        storedPath shouldBe archivePath
                    }
                    transaction { Backup.findById(backupId)?.status } shouldBe "COMPLETED"
                } finally {
                    scope.cancel()
                }
            }
        }
    })
