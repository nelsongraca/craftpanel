package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.EventsCmd
import com.github.dockerjava.api.model.Event
import com.github.dockerjava.api.model.EventActor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockk

class ContainerEventWatcherTest : FunSpec({

    test("reports graceful and crashed managed container deaths") {
        val docker = mockk<DockerClient>()
        val events = mockk<EventsCmd>(relaxed = true)
        val reportedStopped = mutableListOf<String>()
        val reportedCrashes = mutableListOf<String>()
        every { docker.eventsCmd() } returns events
        every { events.withEventTypeFilter("container") } returns events
        every { events.withEventFilter("die") } returns events
        every { events.withLabelFilter("craftpanel.managed=true") } returns events
        every { events.exec(any()) } answers {
            val callback = firstArg<ResultCallback<Event>>()
            callback.onNext(event("srv-1", "0"))
            callback.onNext(event("srv-2", "1"))
            callback
        }

        ContainerEventWatcher(docker).watch(
            shouldReport = { true },
            onContainerCrash = reportedCrashes::add,
            onContainerStopped = reportedStopped::add
        )

        reportedStopped shouldContainExactly listOf("srv-1")
        reportedCrashes shouldContainExactly listOf("srv-2")
    }

    test("suppresses unmanaged events and ignores events without a server id") {
        val docker = mockk<DockerClient>()
        val events = mockk<EventsCmd>(relaxed = true)
        val reported = mutableListOf<String>()
        every { docker.eventsCmd() } returns events
        every { events.withEventTypeFilter("container") } returns events
        every { events.withEventFilter("die") } returns events
        every { events.withLabelFilter("craftpanel.managed=true") } returns events
        every { events.exec(any()) } answers {
            val callback = firstArg<ResultCallback<Event>>()
            callback.onNext(event("", "1"))
            callback.onNext(event("srv-1", "1"))
            callback
        }

        ContainerEventWatcher(docker).watch(
            shouldReport = { false },
            onContainerCrash = reported::add
        )

        reported shouldContainExactly emptyList()
    }
}) {
    companion object {
        private fun event(serverId: String, exitCode: String): Event =
            Event().withEventActor(
                EventActor().withAttributes(
                    mapOf(
                        "craftpanel.server.id" to serverId,
                        "exitCode" to exitCode
                    )
                )
            )
    }
}
