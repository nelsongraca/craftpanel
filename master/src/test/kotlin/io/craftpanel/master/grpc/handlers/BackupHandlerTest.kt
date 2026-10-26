package io.craftpanel.master.grpc.handlers

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.proto.agentMessage
import io.craftpanel.proto.backupCompleteUpdate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class BackupHandlerTest :
    FunSpec({

        test("backup completion carries the archive path into the event") {
            runTest {
                val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 4)
                val collected = mutableListOf<AgentEvent>()
                val job = launch { events.collect { collected.add(it) } }
                yield()

                BackupHandler(events).handleBackupComplete(
                    agentMessage {
                        backupComplete = backupCompleteUpdate {
                            backupId = "b1"
                            serverId = "s1"
                            success = true
                            filePath = "/data/backups/b1.tar.gz"
                            sizeBytes = 1234
                        }
                    }
                )
                yield()

                val event = collected.single() as AgentEvent.BackupCompleteEvent
                event.success shouldBe true
                event.sizeBytes shouldBe 1234
                event.filePath shouldBe "/data/backups/b1.tar.gz"
                job.cancel()
            }
        }
    })
