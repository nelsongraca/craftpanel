package io.craftpanel.master.grpc.handlers

import io.craftpanel.master.domain.AgentEvent
import io.craftpanel.proto.agentMessage
import io.craftpanel.proto.containerMetricsUpdate
import io.craftpanel.proto.jvmStats
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

class ContainerMetricsHandlerTest :
    FunSpec({

        fun handler(events: MutableSharedFlow<AgentEvent>) = ContainerMetricsHandler(events)

        test("maps JVM fields from the proto message into the event") {
            runTest {
                val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 4)
                val collected = mutableListOf<AgentEvent>()
                val job = launch {
                    events.collect { collected.add(it) }
                }
                yield()

                handler(events).handle(
                    agentMessage {
                        containerMetrics = containerMetricsUpdate {
                            serverId = "srv-1"
                            cpuPercent = 5.0
                            ramUsedMb = 256
                            jvm = jvmStats {
                                heapUsedBytes = 1_000
                                heapMaxBytes = 4_000
                                nonHeapUsedBytes = 250
                            }
                        }
                    }
                )
                yield()

                val event = collected.filterIsInstance<AgentEvent.ContainerMetricsEvent>().single()
                event.heapUsedBytes shouldBe 1_000
                event.heapMaxBytes shouldBe 4_000
                event.nonHeapUsedBytes shouldBe 250
                job.cancel()
            }
        }

        test("leaves JVM fields null when the message carries no jvm block") {
            runTest {
                val events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 4)
                val collected = mutableListOf<AgentEvent>()
                val job = launch {
                    events.collect { collected.add(it) }
                }
                yield()

                handler(events).handle(
                    agentMessage {
                        containerMetrics = containerMetricsUpdate {
                            serverId = "srv-1"
                            cpuPercent = 5.0
                            ramUsedMb = 256
                        }
                    }
                )
                yield()

                val event = collected.filterIsInstance<AgentEvent.ContainerMetricsEvent>().single()
                event.heapUsedBytes shouldBe null
                event.heapMaxBytes shouldBe null
                event.nonHeapUsedBytes shouldBe null
                job.cancel()
            }
        }
    })
