package io.craftpanel.agent.docker

/**
 * Pure parsers for the `jattach` output used to sample a server's JVM from inside its container.
 * Kept separate from the Docker exec plumbing so they are testable against captured fixtures.
 *
 * `jattach` is bundled in the itzg images and is the JRE-compatible equivalent of the JDK's
 * jcmd/jstat, so the agent never needs a JDK inside the game container.
 */
object JvmStatsParser {

    /** Separates the `GC.heap_info` block from the `printflag MaxHeapSize` block in probe output. */
    const val MAX_HEAP_MARKER = "===CRAFTPANEL_MAXHEAP==="

    /**
     * Shell run inside the container by the probe: resolve the (in-container) JVM pid, then print
     * `GC.heap_info`, `VM.metaspace` (the heap block no longer carries Metaspace on recent JDKs), and
     * the `MaxHeapSize` flag, separated by [MAX_HEAP_MARKER].
     *
     * PID discovery scans `/proc`, which works on both the Debian-based itzg images and the Alpine
     * fake-server regardless of whether `pgrep` is installed. The process name is `java` on HotSpot.
     */
    val JATTACH_PROBE_SCRIPT: String = """
        P=""
        for d in /proc/[0-9]*; do
          if [ "$(cat "${'$'}d/comm" 2>/dev/null)" = java ]; then P="${'$'}{d#/proc/}"; break; fi
        done
        if [ -n "${'$'}P" ]; then
          jattach "${'$'}P" jcmd GC.heap_info
          jattach "${'$'}P" jcmd VM.metaspace
          echo "$MAX_HEAP_MARKER"
          jattach "${'$'}P" printflag MaxHeapSize
        fi
    """.trimIndent()

    /**
     * Parses the `GC.heap_info` block of the probe output. The layout differs by collector:
     *
     * G1 (the itzg default on modern JDKs, from Aikar flags):
     * ```
     *  garbage-first heap   total 1048576K, committed 524288K, used 123456K [0x..., 0x...)
     *   region size 1024K, 512 young (524288K), 0 survivors (0K)
     *  Metaspace       used 40960K, committed 41984K, reserved 1114112K
     * ```
     *
     * Serial/Parallel/CMS use generation labels instead — note the labels differ from the classic
     * `-verbose:gc` names, and `jcmd` reports them as single tokens (JDK 25 shown):
     * ```
     *  DefNew     total 2432K, used 1268K [...]
     *   eden space 2176K,  49% used [...]
     *  Tenured    total 5504K, used 2330K [...]
     * ```
     * ```
     *  PSYoungGen      total 1536K, used 512K [...]
     *  ParOldGen       total 4096K, used 1024K [...]
     * ```
     *
     * Rather than enumerate every collector's labels, a heap-region line is recognised as one that
     * carries both a `total` and a `used <n>[KMG]` figure; sub-space lines show only a `% used` and
     * are ignored. `used` is summed across the regions. The heap ceiling is read separately from
     * [parseMaxHeapSize]. Returns null when no heap-region line is present (not a JVM / unknown format).
     */
    fun parseHeapInfo(output: String): HeapInfo? {
        val lines = output.lineSequence()
            .map { it.trim() }
            .toList()

        var heapUsedBytes: Long? = null
        var nonHeapUsedBytes: Long? = null

        for (line in lines) {
            // Region summary: "garbage-first heap total ... used ...", "DefNew total ... used ...",
            // "PSYoungGen total ... used ...", "par new generation total ... used ...".
            if (line.contains("total", ignoreCase = true)) {
                usedBytes(line)?.let { heapUsedBytes = (heapUsedBytes ?: 0L) + it }
            }
            // Metaspace (non-heap). Recent JDKs omit it from `GC.heap_info`, so the probe also runs
            // `VM.metaspace`; both emit the same `Metaspace used <n>K` line this matches.
            if (line.startsWith("Metaspace")) {
                usedBytes(line)?.let { nonHeapUsedBytes = it }
            }
        }

        val used = heapUsedBytes ?: return null
        return HeapInfo(
            heapUsedBytes = used,
            nonHeapUsedBytes = nonHeapUsedBytes ?: 0L
        )
    }

    /**
     * Parses `jattach <pid> printflag MaxHeapSize` and returns the heap ceiling (the effective
     * `-Xmx`) in bytes. jattach/jinfo print it as `-XX:MaxHeapSize=<bytes>`; a bare numeric value is
     * also accepted. Returns null when no positive value is present.
     */
    fun parseMaxHeapSize(output: String): Long? {
        val match = MAX_HEAP_SIZE.find(output) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it > 0 }
    }

    /** Heap-region `used` totals from `GC.heap_info`, plus non-heap metaspace. */
    data class HeapInfo(val heapUsedBytes: Long, val nonHeapUsedBytes: Long)

    /** Extracts the `used <n>[KMG]` figure from a heap-info line, in bytes. */
    private fun usedBytes(line: String): Long? {
        val match = USED.find(line) ?: return null
        val value = match.groupValues[1].toLongOrNull() ?: return null
        val multiplier = when (match.groupValues[2].uppercase()) {
            "M" -> 1024L * 1024
            "G" -> 1024L * 1024 * 1024
            else -> 1024L
        }
        return value * multiplier
    }

    private val USED = Regex("used (\\d+)([KMGkmg]?)")

    private val MAX_HEAP_SIZE = Regex("MaxHeapSize=(\\d+)")
}
