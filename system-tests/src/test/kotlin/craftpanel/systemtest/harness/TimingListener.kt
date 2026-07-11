package craftpanel.systemtest.harness

import io.kotest.core.listeners.*
import io.kotest.core.test.TestCase
import io.kotest.core.test.parents
import io.kotest.engine.test.TestResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

object TimingListener : AfterEachListener, BeforeProjectListener, AfterProjectListener {

    private data class Entry(val spec: String, val label: String, val duration: Duration)

    private val entries = CopyOnWriteArrayList<Entry>()
    private val projectStartNanos = AtomicLong(0)

    override suspend fun beforeProject() {
        projectStartNanos.set(System.nanoTime())
    }

    override suspend fun afterEach(testCase: TestCase, result: TestResult) {
        val specName = testCase.spec::class.simpleName ?: "?"
        val path = (
            testCase.parents()
                .map { it.name.name } + testCase.name.name
            ).joinToString(" > ")
        val label = "$specName > $path"
        val ms = result.duration.inWholeMilliseconds
        System.err.println("[timing] %-100s  %6dms".format(label, ms))
        entries += Entry(specName, label, result.duration)
    }

    override suspend fun afterProject() {
        if (entries.isEmpty()) return

        val wallMs = (System.nanoTime() - projectStartNanos.get()) / 1_000_000
        val sumTestMs = entries.sumOf { it.duration.inWholeMilliseconds }

        System.err.println()
        System.err.println("─── Slowest tests ─────────────────────────────────────────────────────────")
        entries.sortedByDescending { it.duration }
            .take(20)
            .forEach { e ->
                System.err.println("  %6dms  %s".format(e.duration.inWholeMilliseconds, e.label))
            }

        System.err.println()
        System.err.println("─── Per-spec totals ───────────────────────────────────────────────────────")
        entries.groupBy { it.spec }
            .map { (spec, v) -> Triple(spec, v.sumOf { it.duration.inWholeMilliseconds }, v.size) }
            .sortedByDescending { it.second }
            .forEach { (spec, ms, count) ->
                System.err.println("  %6dms  %3d tests  %s".format(ms, count, spec))
            }

        System.err.println()
        System.err.println("─── Run totals ────────────────────────────────────────────────────────────")
        System.err.println("  tests run      : ${entries.size}")
        System.err.println("  sum test time  : ${fmt(sumTestMs)}  (test bodies only)")
        System.err.println("  wall clock     : ${fmt(wallMs)}  (incl. stack startup + spec setup)")
        System.err.println("  setup overhead : ${fmt(wallMs - sumTestMs)}  (wall - sum test time)")
        System.err.println("────────────────────────────────────────────────────────────────────────────")
    }

    private fun fmt(ms: Long): String {
        val totalSec = ms / 1000.0
        val min = (totalSec / 60).toInt()
        val sec = totalSec % 60
        return if (min > 0) "%dm %04.1fs (%dms)".format(min, sec, ms) else "%.1fs (%dms)".format(totalSec, ms)
    }
}
