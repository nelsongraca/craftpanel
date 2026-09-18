package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.EventsCmd
import com.github.dockerjava.api.model.Event
import com.github.dockerjava.api.model.EventActor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ContainerEventWatcherTest :
    FunSpec({

        fun dockerWith(handler: (ResultCallback<Event>) -> Unit): DockerClient {
            val docker = mockk<DockerClient>()
            val events = mockk<EventsCmd>(relaxed = true)
            every { docker.eventsCmd() } returns events
            every { events.withEventTypeFilter("container") } returns events
            every { events.withEventFilter("die") } returns events
            every { events.withLabelFilter("craftpanel.managed=true") } returns events
            every { events.exec(any()) } answers {
                val callback = firstArg<ResultCallback<Event>>()
                callback.onStart(mockk(relaxed = true))
                handler(callback)
                callback
            }
            return docker
        }

        test("reports graceful and crashed managed container deaths") {
            val latch = CountDownLatch(1)
            val reportedStopped = CopyOnWriteArrayList<String>()
            val reportedCrashes = CopyOnWriteArrayList<String>()
            val docker = dockerWith { callback ->
                callback.onNext(event("srv-1", "0"))
                callback.onNext(event("srv-2", "1"))
                latch.countDown()
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            val watcher = ContainerEventWatcher(docker, initialBackoffMs = 10, maxBackoffMs = 20).watch(
                scope = scope,
                shouldReport = { true },
                onContainerCrash = reportedCrashes::add,
                onContainerStopped = reportedStopped::add
            )

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            reportedStopped shouldContainExactly listOf("srv-1")
            reportedCrashes shouldContainExactly listOf("srv-2")
            watcher.close()
            scope.cancel()
        }

        test("suppresses unmanaged events and ignores events without a server id") {
            val latch = CountDownLatch(1)
            val reported = CopyOnWriteArrayList<String>()
            val docker = dockerWith { callback ->
                callback.onNext(event("", "1"))
                callback.onNext(event("srv-1", "1"))
                latch.countDown()
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            val watcher = ContainerEventWatcher(docker, initialBackoffMs = 10, maxBackoffMs = 20).watch(
                scope = scope,
                shouldReport = { false },
                onContainerCrash = reported::add
            )

            latch.await(5, TimeUnit.SECONDS) shouldBe true
            reported shouldContainExactly emptyList()
            watcher.close()
            scope.cancel()
        }

        test("resubscribes after the stream completes") {
            val crashes = CopyOnWriteArrayList<String>()
            val invocation = AtomicInteger(0)
            val secondSubscription = CountDownLatch(1)
            val docker = dockerWith { callback ->
                when (invocation.incrementAndGet()) {
                    1 -> {
                        callback.onNext(event("srv-1", "1"))
                        callback.onComplete()
                    }

                    else -> {
                        callback.onNext(event("srv-2", "1"))
                        secondSubscription.countDown()
                    }
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            val watcher = ContainerEventWatcher(docker, initialBackoffMs = 10, maxBackoffMs = 20).watch(
                scope = scope,
                shouldReport = { true },
                onContainerCrash = crashes::add
            )

            secondSubscription.await(5, TimeUnit.SECONDS) shouldBe true
            invocation.get() shouldBe 2
            crashes shouldContainExactly listOf("srv-1", "srv-2")
            watcher.close()
            scope.cancel()
        }
    }) {
    companion object {
        private fun event(serverId: String, exitCode: String): Event = Event().withEventActor(
            EventActor().withAttributes(
                mapOf(
                    "craftpanel.server.id" to serverId,
                    "exitCode" to exitCode
                )
            )
        )
    }
}
