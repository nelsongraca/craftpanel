package io.craftpanel.agent.grpc.handlers

import io.craftpanel.agent.grpc.AgentOutbound
import io.craftpanel.proto.*
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

private class FakeConsoleSession(
    private val outputFlow: Flow<ByteArray>,
) : ConsoleSession {
    val writtenData = mutableListOf<ByteArray>()
    var closed = false

    override val output: Flow<ByteArray> = outputFlow

    override fun writeInput(data: ByteArray) {
        writtenData.add(data)
    }

    override fun close() {
        closed = true
    }
}

private class FakeLogFetcher(
    private val result: List<String>?,
    private val throwOnFetch: Boolean = false,
) : LogFetcher {
    var fetchCount = 0

    override suspend fun fetchLogs(serverId: String, tailLines: Int): List<String>? {
        fetchCount++
        if (throwOnFetch) throw RuntimeException("log fetch failed")
        return result
    }
}

class ConsoleHandlerTest : FunSpec({

    val nodeId = "test-node"

    fun handler(sessionFactory: ConsoleSession.Factory, logFetcher: LogFetcher) =
        ConsoleHandler(sessionFactory, logFetcher, Dispatchers.Unconfined)

    // ── handleConsoleAttach ──────────────────────────────────────────────────

    test("attach creates session and forwards output frames") {
        runBlocking {
            val session = FakeConsoleSession(
                flow { emit("hello".toByteArray()); emit("world".toByteArray()) }
            )
            var factoryCalled = false
            val factory = ConsoleSession.Factory { serverId ->
                serverId shouldBe "srv-1"
                factoryCalled = true
                session
            }
            val logFetcher = FakeLogFetcher(emptyList())
            val handler = handler(factory, logFetcher)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)
            val outbound = AgentOutbound(channel, nodeId)

            handler.handleConsoleAttach(
                consoleAttach { requestId = "req-1"; serverId = "srv-1" },
                outbound
            )

            val msgs = channel.toList()

            withClue("factory should have been called") { factoryCalled shouldBe true }
            withClue("should have forwarded 2 frames") {
                val consoleOutputs = msgs.filter { it.hasConsoleOutput() && !it.consoleOutput.closed }
                consoleOutputs.size shouldBe 2
                consoleOutputs[0].consoleOutput.data.toStringUtf8() shouldBe "hello"
                consoleOutputs[1].consoleOutput.data.toStringUtf8() shouldBe "world"
            }
        }
    }

    test("attach ignores duplicate requestId") {
        runBlocking {
            var createCount = 0
            val session = FakeConsoleSession(flow { emit("data".toByteArray()) })
            val factory = ConsoleSession.Factory {
                createCount++
                session
            }
            val logFetcher = FakeLogFetcher(emptyList())
            val handler = handler(factory, logFetcher)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)
            val outbound = AgentOutbound(channel, nodeId)

            handler.handleConsoleAttach(
                consoleAttach { requestId = "req-1"; serverId = "srv-1" },
                outbound
            )
            handler.handleConsoleAttach(
                consoleAttach { requestId = "req-1"; serverId = "srv-2" },
                outbound
            )

            withClue("factory should have been called only once") { createCount shouldBe 1 }
        }
    }

    test("attach sends closed when factory returns null") {
        runBlocking {
            val factory = ConsoleSession.Factory { null }
            val logFetcher = FakeLogFetcher(emptyList())
            val handler = handler(factory, logFetcher)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)
            val outbound = AgentOutbound(channel, nodeId)

            handler.handleConsoleAttach(
                consoleAttach { requestId = "req-1"; serverId = "srv-missing" },
                outbound
            )

            val msgs = channel.toList()
            msgs.single().let { msg ->
                msg.hasConsoleOutput() shouldBe true
                msg.consoleOutput.requestId shouldBe "req-1"
                msg.consoleOutput.closed shouldBe true
            }
        }
    }

    test("attach sends closed and STOPPED when container dies") {
        runBlocking {
            val session = FakeConsoleSession(flow { emit("last words".toByteArray()) })
            val factory = ConsoleSession.Factory { session }
            val logFetcher = FakeLogFetcher(emptyList())
            val handler = handler(factory, logFetcher)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)
            val outbound = AgentOutbound(channel, nodeId)

            handler.handleConsoleAttach(
                consoleAttach { requestId = "req-1"; serverId = "srv-1" },
                outbound
            )

            val msgs = channel.toList()

            val consoleOutputs = msgs.filter { it.hasConsoleOutput() }
            consoleOutputs.any { it.consoleOutput.closed } shouldBe true
            val statusUpdates = msgs.filter { it.hasServerStatus() }
            statusUpdates.any {
                it.serverStatus.status == ServerStatusUpdate.ServerStatus.STOPPED
            } shouldBe true
        }
    }

    // ── handleConsoleInput ───────────────────────────────────────────────────

    test("input writes data to session") {
        runBlocking {
            val session = FakeConsoleSession(flow { emit("data".toByteArray()) })
            val factory = ConsoleSession.Factory { session }
            val logFetcher = FakeLogFetcher(emptyList())
            val handler = handler(factory, logFetcher)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)
            val outbound = AgentOutbound(channel, nodeId)

            handler.handleConsoleAttach(
                consoleAttach { requestId = "req-1"; serverId = "srv-1" },
                outbound
            )

            handler.handleConsoleInput(
                consoleInput {
                    requestId = "req-1"
                    data = com.google.protobuf.ByteString.copyFromUtf8("cmd\n")
                }
            )

            session.writtenData.map { it.toString(Charsets.UTF_8) } shouldContainExactly listOf("cmd\n")
        }
    }

    test("input ignored when session does not exist") {
        runBlocking {
            val session = FakeConsoleSession(flow { })
            val factory = ConsoleSession.Factory { session }
            val logFetcher = FakeLogFetcher(emptyList())
            val handler = handler(factory, logFetcher)

            handler.handleConsoleInput(
                consoleInput {
                    requestId = "nonexistent"
                    data = com.google.protobuf.ByteString.copyFromUtf8("data\n")
                }
            )

            session.writtenData.isEmpty() shouldBe true
        }
    }

    // ── handleConsoleDetach ──────────────────────────────────────────────────

    test("detach cancels session and removes from map") {
        runBlocking {
            val session = FakeConsoleSession(flow { emit("data".toByteArray()) })
            val factory = ConsoleSession.Factory { session }
            val logFetcher = FakeLogFetcher(emptyList())
            val handler = handler(factory, logFetcher)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)
            val outbound = AgentOutbound(channel, nodeId)

            handler.handleConsoleAttach(
                consoleAttach { requestId = "req-1"; serverId = "srv-1" },
                outbound
            )

            handler.handleConsoleDetach(consoleDetach { requestId = "req-1" })

            session.closed shouldBe true
        }
    }

    test("detach ignored when session does not exist") {
        runBlocking {
            val h = handler(ConsoleSession.Factory { null }, FakeLogFetcher(emptyList()))
            h.handleConsoleDetach(consoleDetach { requestId = "nonexistent" })
        }
    }

    // ── handleFetchContainerLogs ─────────────────────────────────────────────

    test("fetchLogs returns lines when fetcher succeeds") {
        runBlocking {
            val logFetcher = FakeLogFetcher(listOf("line1", "line2"))
            val h = handler(ConsoleSession.Factory { null }, logFetcher)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)
            val outbound = AgentOutbound(channel, nodeId)

            h.handleFetchContainerLogs(
                fetchContainerLogsRequest {
                    requestId = "req-1"
                    serverId = "srv-1"
                    tailLines = 50
                },
                outbound
            )

            val msgs = channel.toList()
            val logResponse = msgs.single()
            logResponse.hasFetchContainerLogsResponse() shouldBe true
            logResponse.fetchContainerLogsResponse.let { resp ->
                resp.requestId shouldBe "req-1"
                resp.linesList shouldContainExactly listOf("line1", "line2")
                resp.closed shouldBe false
            }
            logFetcher.fetchCount shouldBe 1
        }
    }

    test("fetchLogs sends closed when fetcher returns null") {
        runBlocking {
            val logFetcher = FakeLogFetcher(null)
            val h = handler(ConsoleSession.Factory { null }, logFetcher)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)
            val outbound = AgentOutbound(channel, nodeId)

            h.handleFetchContainerLogs(
                fetchContainerLogsRequest {
                    requestId = "req-1"
                    serverId = "srv-missing"
                    tailLines = 10
                },
                outbound
            )

            val msgs = channel.toList()
            msgs.single().let { msg ->
                msg.hasFetchContainerLogsResponse() shouldBe true
                msg.fetchContainerLogsResponse.closed shouldBe true
            }
        }
    }

    test("fetchLogs sends closed on exception") {
        runBlocking {
            val logFetcher = FakeLogFetcher(null, throwOnFetch = true)
            val h = handler(ConsoleSession.Factory { null }, logFetcher)
            val channel = Channel<AgentMessage>(Channel.UNLIMITED)
            val outbound = AgentOutbound(channel, nodeId)

            h.handleFetchContainerLogs(
                fetchContainerLogsRequest {
                    requestId = "req-1"
                    serverId = "srv-1"
                    tailLines = 10
                },
                outbound
            )

            val msgs = channel.toList()
            msgs.single().let { msg ->
                msg.hasFetchContainerLogsResponse() shouldBe true
                msg.fetchContainerLogsResponse.closed shouldBe true
            }
        }
    }
})

private fun Channel<AgentMessage>.toList(): List<AgentMessage> {
    close()
    return buildList {
        while (true) {
            val r = tryReceive()
            if (r.isSuccess) add(r.getOrThrow()) else break
        }
    }
}
