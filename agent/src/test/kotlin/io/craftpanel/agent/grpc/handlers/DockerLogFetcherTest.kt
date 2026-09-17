package io.craftpanel.agent.grpc.handlers

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import io.craftpanel.agent.docker.ContainerManager
import io.craftpanel.proto.ContainerState
import io.craftpanel.proto.containerState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.mockk.every
import io.mockk.mockk

class DockerLogFetcherTest : FunSpec({

    test("returns null when the server container is not managed") {
        val manager = mockk<ContainerManager>()
        every { manager.listContainers() } returns emptyList()

        DockerLogFetcher(manager).fetchLogs("missing", 20).shouldBeNull()
    }

    test("collects log frames until the Docker callback completes") {
        val manager = mockk<ContainerManager>()
        val container = containerState {
            serverId = "srv-1"
            containerName = "craftpanel-srv-1"
        }
        every { manager.listContainers() } returns listOf(container)
        every { manager.fetchLogs(any(), 20, any()) } answers {
            val callback = thirdArg<ResultCallback<Frame>>()
            callback.onNext(frame("first"))
            callback.onNext(frame("second"))
            callback.onComplete()
            callback
        }

        DockerLogFetcher(manager).fetchLogs("srv-1", 20) shouldContainExactly listOf("first", "second")
    }

    test("returns collected frames when Docker reports an error") {
        val manager = mockk<ContainerManager>()
        every { manager.listContainers() } returns listOf(containerState {
            serverId = "srv-1"
            containerName = "craftpanel-srv-1"
        })
        every { manager.fetchLogs(any(), any(), any()) } answers {
            val callback = thirdArg<ResultCallback<Frame>>()
            callback.onNext(frame("before-error"))
            callback.onError(IllegalStateException("stream failed"))
            callback
        }

        DockerLogFetcher(manager).fetchLogs("srv-1", 5) shouldContainExactly listOf("before-error")
    }
}) {
    companion object {
        private fun frame(value: String): Frame = mockk {
            every { payload } returns value.toByteArray()
        }
    }
}
