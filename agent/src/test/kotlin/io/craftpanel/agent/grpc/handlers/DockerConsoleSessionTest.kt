package io.craftpanel.agent.grpc.handlers

import io.craftpanel.agent.docker.FakeContainerManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.TimeUnit

/**
 * The fake container manager's [attachInteractive] returns without ever draining the stdin
 * stream — the worst case for a blocking write. [DockerConsoleSession.writeInput] must never
 * block the caller in that situation, since the caller is the control-stream dispatcher.
 */
class DockerConsoleSessionTest :
    FunSpec({

        fun session(manager: FakeContainerManager = FakeContainerManager()) = DockerConsoleSession("craftpanel-srv-1", manager)

        test("writeInput does not block when the attach reader never drains stdin") {
            val s = session()

            val returned = CompletableFutureBridge.runWithin(2, TimeUnit.SECONDS) {
                repeat(DockerConsoleSession.INPUT_QUEUE_CAPACITY) { i ->
                    s.writeInput("cmd-$i\n".toByteArray())
                }
            }

            returned shouldBe true
            s.close()
        }

        test("writeInput drops silently on queue overflow without blocking") {
            val s = session()

            val returned = CompletableFutureBridge.runWithin(2, TimeUnit.SECONDS) {
                repeat(DockerConsoleSession.INPUT_QUEUE_CAPACITY * 4) { i ->
                    s.writeInput("cmd-$i\n".toByteArray())
                }
            }

            returned shouldBe true
            s.close()
        }

        test("close is idempotent") {
            val s = session()
            s.writeInput("cmd\n".toByteArray())
            s.close()
            s.close()
        }
    })

private object CompletableFutureBridge {
    /** Runs [block] on a separate thread; true if it exits within the timeout, false if it hangs. */
    fun runWithin(timeout: Long, unit: TimeUnit, block: () -> Unit): Boolean {
        val worker = Thread(block, "test-write-input")
        worker.isDaemon = true
        worker.start()
        worker.join(unit.toMillis(timeout))
        return !worker.isAlive
    }
}
