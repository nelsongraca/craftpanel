package io.craftpanel.agent.docker

/**
 * Parsed JVM memory figures, in bytes. `max` is the heap ceiling (`-Xmx` effective value), so
 * [heapUsedBytes] / [heapMaxBytes] is the primary "how much of its RAM is the JVM really using"
 * signal — the container's cgroup figure approximates the off-heap remainder.
 */
data class ParsedJvmStats(val heapUsedBytes: Long, val heapMaxBytes: Long, val nonHeapUsedBytes: Long)

/**
 * Pure parsers for the jcmd/jstat output used to sample a server's JVM from inside its container.
 * Kept separate from the Docker exec plumbing so they are testable against captured fixtures.
 */
object JvmStatsParser {

    /**
     * Parses `jcmd <pid> GC.heap_info`. The layout differs by collector:
     *
     * G1 (the itzg default on modern JDKs, from Aikar flags):
     * ```
     *  garbage-first heap   total 1048576K, used 524288K [0x..., 0x...)
     *   region size 1024K, 512 young (524288K), 0 survivors (0K)
     *  Metaspace       used 40960K, committed 41984K, reserved 1114112K
     * ```
     *
     * Serial/Parallel/CMS use generation lines instead:
     * ```
     *  def new generation   total 157248K, used 12345K [...]
     *  eden space 139776K,  8% used [...]
     *  tenured generation   total 349568K, used 200000K [...]
     *  Metaspace       used 40960K, committed 41984K, reserved 1114112K
     * ```
     *
     * `used` totals are summed across the heap regions; `total` (the live heap size, not the max)
     * is deliberately NOT used as `max` — `max` is read from `jstat -gc` instead. Returns null when
     * no heap line is present (not a JVM / unrecognised format).
     */
    fun parseHeapInfo(output: String): HeapInfo? {
        val lines = output.lineSequence()
            .map { it.trim() }
            .toList()

        var heapUsedKb: Long? = null
        var nonHeapUsedKb: Long? = null

        // G1: "garbage-first heap total <n>K, used <n>K [...]"
        for (line in lines) {
            if (line.startsWith("garbage-first heap") || line.startsWith("garbage first heap")) {
                usedKb(line)?.let { heapUsedKb = it }
            }
            // Parallel/Shenandoah: "parallel heap" — same total/used shape.
            if (line.startsWith("parallel heap")) {
                usedKb(line)?.let { heapUsedKb = it }
            }
            // Serial/CMS generation lines carrying total+used.
            for (prefix in GENERATION_PREFIXES) {
                if (line.startsWith(prefix) && line.contains(" used ")) {
                    val used = usedKb(line) ?: continue
                    heapUsedKb = (heapUsedKb ?: 0L) + used
                }
            }
        }

        // Metaspace (non-heap) — present in most formats.
        for (line in lines) {
            if (line.startsWith("Metaspace")) {
                usedKb(line)?.let { nonHeapUsedKb = it }
            }
        }

        val used = heapUsedKb ?: return null
        return HeapInfo(
            heapUsedBytes = used * 1024,
            nonHeapUsedBytes = (nonHeapUsedKb ?: 0L) * 1024
        )
    }

    /**
     * Parses `jstat -gc <pid>` and returns the heap max in bytes.
     *
     * The header is a column of names; the data line is the values in the same order. jstat reports
     * capacities in KB. Heap max = sum of the generation capacities that make it up:
     * G1 uses only `S0C S1C EC OC`; the generational collectors add `S0C S1C EC OC` too (the
     * `PC/PU` permanent/metaspace pair is non-heap and excluded). Because different JDKs emit
     * different column sets, the parser locates the columns by header name rather than by index.
     *
     * Returns null when the output is empty/malformed or no capacity columns are found.
     */
    fun parseJstatMax(output: String): Long? {
        val lines = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.size < 2) return null

        val header = lines[0].split(WHITESPACE)
        val data = lines[1].split(WHITESPACE)
        if (header.size != data.size) return null

        val byName = header.zip(data)
            .toMap()
        val maxKb = HEAP_CAPACITY_COLUMNS.sumOf { col ->
            byName[col]?.toDoubleOrNull() ?: 0.0
        }
        return if (maxKb > 0.0) (maxKb * 1024).toLong() else null
    }

    /** Heap-region `used` totals from `GC.heap_info`, plus non-heap metaspace. */
    data class HeapInfo(val heapUsedBytes: Long, val nonHeapUsedBytes: Long)

    /** Extracts the `used <n>K` figure from a heap-info line. */
    private fun usedKb(line: String): Long? {
        val idx = line.indexOf("used ")
        if (idx < 0) return null
        val rest = line.substring(idx + "used ".length)
        val token = rest.takeWhile { it.isDigit() }.takeIf { it.isNotEmpty() } ?: return null
        return token.toLongOrNull()
    }

    private val GENERATION_PREFIXES = listOf(
        "def new generation",
        "tenured generation",
        "new generation",
        "young generation",
        "old generation"
    )

    // Capacity columns that add up to the heap ceiling. `PC`/`PU` (permanent) are non-heap and
    // intentionally excluded.
    private val HEAP_CAPACITY_COLUMNS = listOf("S0C", "S1C", "EC", "OC")

    private val WHITESPACE = Regex("\\s+")
}
