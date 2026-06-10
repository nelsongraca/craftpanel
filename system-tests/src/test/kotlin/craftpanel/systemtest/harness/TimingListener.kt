package craftpanel.systemtest.harness

import io.kotest.core.listeners.AfterEachListener
import io.kotest.core.listeners.AfterProjectListener
import io.kotest.core.test.TestCase
import io.kotest.core.test.parents
import io.kotest.engine.test.TestResult
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

object TimingListener : AfterEachListener, AfterProjectListener {

    private data class Entry(val label: String, val duration: Duration)

    private val entries = CopyOnWriteArrayList<Entry>()

    override suspend fun afterEach(testCase: TestCase, result: TestResult) {
        val specName = testCase.spec::class.simpleName ?: "?"
        val path = (testCase.parents()
            .map { it.name.name } + testCase.name.name).joinToString(" > ")
        val label = "$specName > $path"
        val ms = result.duration.inWholeMilliseconds
        System.err.println("[timing] %-100s  %6dms".format(label, ms))
        entries += Entry(label, result.duration)
    }

    override suspend fun afterProject() {
        if (entries.isEmpty()) return
        val top = entries.sortedByDescending { it.duration }
            .take(20)
        System.err.println()
        System.err.println("─── Slowest tests ─────────────────────────────────────────────────────────")
        top.forEach { e ->
            System.err.println("  %6dms  %s".format(e.duration.inWholeMilliseconds, e.label))
        }
        System.err.println("────────────────────────────────────────────────────────────────────────────")
    }
}
