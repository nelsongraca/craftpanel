package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.Statistics
import com.google.protobuf.timestamp
import io.craftpanel.proto.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

open class MetricsCollector(private val docker: DockerClient) {

    private val log = LoggerFactory.getLogger(MetricsCollector::class.java)

    private data class CpuSnapshot(val idle: Long, val total: Long)
    private data class CoreSnapshot(val idle: Long, val total: Long)

    private var prevCpu: CpuSnapshot? = null
    private var prevCoreCpus: List<CoreSnapshot> = emptyList()

    fun collect(): NodeMetricsUpdate {
        val now = Instant.now()
        val (cpuPercent, cpuPerCore, ramUsedMb, ramTotalMb) = readProcMetrics()
        val (netIn, netOut) = readNetMetrics()
        val (diskUsed, diskTotal) = readDiskMetrics()

        return nodeMetricsUpdate {
            recordedAt = timestamp {
                seconds = now.epochSecond
                nanos = now.nano
            }
            this.cpuPercent = cpuPercent
            this.cpuPerCore.addAll(cpuPerCore)
            this.ramUsedMb = ramUsedMb
            this.ramTotalMb = ramTotalMb
            netInBytes = netIn
            netOutBytes = netOut
            diskUsedBytes = diskUsed
            diskTotalBytes = diskTotal
        }
    }

    fun collectContainerMetrics(serverId: String, containerId: String, cpuLimitMillicores: Int = 0): ContainerMetricsUpdate? {
        return runCatching {
            val latch = CountDownLatch(1)
            var captured: Statistics? = null

            val callback = object : ResultCallback<Statistics> {
                override fun onStart(c: Closeable?) {}
                override fun onNext(s: Statistics) {
                    captured = s
                    latch.countDown()
                }

                override fun onError(t: Throwable) {
                    log.warn("Stats error for $containerId: ${t.message}")
                    latch.countDown()
                }

                override fun onComplete() {}
                override fun close() {}
            }

            docker.statsCmd(containerId)
                .withNoStream(true)
                .exec(callback)
            if (!latch.await(5, TimeUnit.SECONDS)) return null

            val s = captured ?: return null
            val cpu = s.cpuStats
            val preCpu = s.preCpuStats
            val mem = s.memoryStats

            val cpuDelta = (cpu.cpuUsage?.totalUsage ?: 0L) - (preCpu.cpuUsage?.totalUsage ?: 0L)
            val systemDelta = (cpu.systemCpuUsage ?: 0L) - (preCpu.systemCpuUsage ?: 0L)
            val numCpus = (
                cpu.onlineCpus?.toInt()
                    ?.takeIf { it > 0 }
                    ?: cpu.cpuUsage?.percpuUsage?.size?.takeIf { it > 0 } ?: 1
                )
            // Host-wide cores consumed over the interval. `numCpus` is the daemon-reported online
            // CPU count, so this is core-relative (1 core fully used = 1.0), independent of the cap.
            val coresUsed = if (systemDelta > 0) cpuDelta.toDouble() / systemDelta * numCpus else 0.0
            val cpuPct = normalizeCpuPercent(coresUsed, numCpus, cpuLimitMillicores)

            val statsConfig = mem.stats
            val cache = statsConfig?.cache ?: statsConfig?.inactiveFile ?: 0L
            val ramUsedMb = ((mem.usage ?: 0L) - cache).coerceAtLeast(0L) / (1024 * 1024)

            val netIn = s.networks?.values?.sumOf { it.rxBytes ?: 0L } ?: 0L
            val netOut = s.networks?.values?.sumOf { it.txBytes ?: 0L } ?: 0L

            val blkio = s.blkioStats?.ioServiceBytesRecursive.orEmpty()
            val blockIn = blkio.filter { it.op.equals("read", ignoreCase = true) }
                .sumOf { it.value ?: 0L }
            val blockOut = blkio.filter { it.op.equals("write", ignoreCase = true) }
                .sumOf { it.value ?: 0L }

            val now = Instant.now()
            containerMetricsUpdate {
                this.serverId = serverId
                recordedAt = timestamp {
                    seconds = now.epochSecond
                    nanos = now.nano
                }
                this.cpuPercent = cpuPct
                this.ramUsedMb = ramUsedMb.toInt()
                netInBytes = netIn
                netOutBytes = netOut
                blockInBytes = blockIn
                blockOutBytes = blockOut
            }
        }.getOrElse {
            log.warn("Failed to collect container metrics for $containerId", it)
            null
        }
    }

    /**
     * Samples the server's JVM heap (used/max) and non-heap usage from inside its container.
     *
     * Uses the `jattach` utility bundled in the itzg images (installed via apk/apt). jattach speaks
     * the JVM dynamic-attach mechanism over a UNIX socket inside the container, so no network port
     * is involved — it works on the isolated server network and needs only a JRE, not a JDK.
     *
     * Best-effort and nullable: returns null when the container is not running, has no JVM (e.g.
     * PicoLimbo), or is not attachable (non-HotSpot runtime, attach disabled). Never throws and never
     * zeroes a sample — an absent value means "no data", so history is not polluted with fake dips.
     */
    fun collectJvmStats(containerId: String): JvmStats? = runCatching {
        // One exec per sample: resolve the in-container JVM pid, then read heap usage and the -Xmx
        // ceiling in the same shell. The marker separates the two outputs for the parser.
        val output = execCapture(containerId, listOf("sh", "-c", JvmStatsParser.JATTACH_PROBE_SCRIPT))
        if (output.isNullOrBlank()) {
            // No JVM in the container (e.g. PicoLimbo) or the probe produced nothing — not an error.
            log.debug("JVM probe returned no output for $containerId")
            return null
        }
        val heap = JvmStatsParser.parseHeapInfo(output.substringBefore(JvmStatsParser.MAX_HEAP_MARKER))
        if (heap == null) {
            log.warn("Unrecognised JVM heap format for $containerId: {}", output.take(200))
            return null
        }
        val heapMax = JvmStatsParser.parseMaxHeapSize(output.substringAfter(JvmStatsParser.MAX_HEAP_MARKER, ""))
        if (heapMax == null) {
            log.warn("JVM probe could not parse MaxHeapSize for $containerId: {}", output.take(200))
            return null
        }
        log.debug(
            "JVM sample for $containerId: heapUsed={} heapMax={} nonHeap={}",
            heap.heapUsedBytes,
            heapMax,
            heap.nonHeapUsedBytes
        )
        jvmStats {
            heapUsedBytes = heap.heapUsedBytes
            heapMaxBytes = heapMax
            nonHeapUsedBytes = heap.nonHeapUsedBytes
        }
    }.getOrElse {
        log.warn("Failed to collect JVM stats for $containerId: ${it.message}")
        null
    }

    /**
     * Probes the container for its player list by running the image's bundled `mc-monitor` inside
     * it: `mc-monitor status --json --host localhost --port <internalListenPort>`. Network-independent
     * — the probe runs in the container's own namespace and never involves mc-router or the Docker
     * network layout. [useProxy] adds `--use-proxy` for proxies whose listener expects the HAProxy
     * PROXY protocol.
     */
    fun collectPlayerCount(serverId: String, containerId: String, internalListenPort: Int, useProxy: Boolean): PlayerUpdate? {
        val args = buildList {
            add("mc-monitor")
            add("status")
            add("--json")
            add("--host")
            add("localhost")
            add("--port")
            add(internalListenPort.toString())
            add("--timeout")
            add("3s")
            if (useProxy) add("--use-proxy")
        }
        val text = execCapture(containerId, args) ?: return null
        val status = parseMcMonitorStatus(text) ?: return null
        val now = Instant.now()
        return playerUpdate {
            this.serverId = serverId
            playerCount = status.count
            playerNames.addAll(status.names)
            recordedAt = timestamp {
                seconds = now.epochSecond
                nanos = now.nano
            }
        }
    }

    /** Runs [args] inside [containerId] and returns stdout, or null on failure/timeout. */
    private fun execCapture(containerId: String, args: List<String>, timeoutSeconds: Long = 5): String? = runCatching {
        val execId = docker.execCreateCmd(containerId)
            .withAttachStdout(true)
            .withCmd(*args.toTypedArray())
            .exec().id
        val out = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        docker.execStartCmd(execId)
            .exec(object : ResultCallback.Adapter<Frame>() {
                override fun onNext(frame: Frame) {
                    out.write(frame.payload)
                }

                override fun onComplete() {
                    latch.countDown()
                }

                override fun onError(t: Throwable) {
                    latch.countDown()
                }
            })
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) return null
        out.toString(Charsets.UTF_8.name())
    }.getOrElse {
        log.debug("container exec failed for $containerId: ${it.message}")
        null
    }

    fun collectCapacity(): Pair<Int, Int> {
        val totalRamMb = runCatching {
            ((parseMemInfo()["MemTotal"] ?: 0L) / 1024).toInt()
        }.getOrElse { 0 }
        val totalCpuMillicores = hostCpuCount() * 1000
        return Pair(totalRamMb, totalCpuMillicores)
    }

    /**
     * Host CPU count, read from `/proc/cpuinfo` like the other metrics sources. Deliberately NOT
     * `Runtime.availableProcessors()`: that is cgroup-aware and returns the agent container's CPU
     * limit (often 1), not the node's real capacity. Falls back to the JVM count only if
     * `/proc/cpuinfo` is unreadable.
     */
    private fun hostCpuCount(): Int = runCatching {
        File("/proc/cpuinfo").readLines()
            .count { it.startsWith("processor") }
    }.getOrElse { 0 }
        .takeIf { it > 0 } ?: Runtime.getRuntime()
        .availableProcessors()

    private data class ProcMetrics(val cpuPercent: Double, val cpuPerCore: List<Double>, val ramUsedMb: Int, val ramTotalMb: Int)

    private fun readProcMetrics(): ProcMetrics = runCatching {
        val memInfo = parseMemInfo()
        val totalKb = memInfo["MemTotal"] ?: 0L
        val availKb = memInfo["MemAvailable"] ?: 0L
        val (cpuPercent, cpuPerCore) = readCpuPercent()
        ProcMetrics(
            cpuPercent = cpuPercent,
            cpuPerCore = cpuPerCore,
            ramUsedMb = ((totalKb - availKb) / 1024).toInt(),
            ramTotalMb = (totalKb / 1024).toInt()
        )
    }.getOrElse {
        log.warn("Failed to read /proc metrics", it)
        ProcMetrics(0.0, emptyList(), 0, 0)
    }

    private fun readCpuPercent(): Pair<Double, List<Double>> {
        val lines = runCatching { File("/proc/stat").readLines() }.getOrElse { return 0.0 to emptyList() }
        val totalLine = lines.firstOrNull { it.startsWith("cpu ") } ?: return 0.0 to emptyList()
        val coreLines = lines.filter { it.matches(Regex("cpu[0-9]+.*")) }

        fun parseLine(line: String): Pair<Long, Long> {
            val parts = line.trim()
                .split("\\s+".toRegex())
                .drop(1)
                .map { it.toLongOrNull() ?: 0L }
            val idle = parts.getOrElse(3) { 0L } + parts.getOrElse(4) { 0L }
            return idle to parts.sum()
        }

        val (idle, total) = parseLine(totalLine)
        val corePairs = coreLines.map { parseLine(it) }

        val prev = prevCpu
        val prevCores = prevCoreCpus
        prevCpu = CpuSnapshot(idle, total)
        prevCoreCpus = corePairs.map { (i, t) -> CoreSnapshot(i, t) }

        if (prev == null) return 0.0 to emptyList()

        val idleDelta = idle - prev.idle
        val totalDelta = total - prev.total
        val percent = if (totalDelta > 0) (1.0 - idleDelta.toDouble() / totalDelta) * 100.0 else 0.0

        val perCore = corePairs.mapIndexed { i, (ci, ct) ->
            val pc = prevCores.getOrNull(i)
            if (pc == null || ct - pc.total <= 0) {
                0.0
            } else {
                (1.0 - (ci - pc.idle).toDouble() / (ct - pc.total)) * 100.0
            }
        }
        return percent to perCore
    }

    private fun readNetMetrics(): Pair<Long, Long> {
        return runCatching {
            var rxTotal = 0L
            var txTotal = 0L
            File("/proc/net/dev").readLines()
                .drop(2)
                .forEach { line ->
                    val trimmed = line.trim()
                    val colon = trimmed.indexOf(':')
                    if (colon < 0) return@forEach
                    val iface = trimmed.substring(0, colon)
                        .trim()
                    if (iface == "lo" || iface.startsWith("veth") || iface.startsWith("br-") || iface == "docker0") return@forEach
                    val parts = trimmed.substring(colon + 1)
                        .trim()
                        .split("\\s+".toRegex())
                    rxTotal += parts.getOrElse(0) { "0" }
                        .toLongOrNull() ?: 0L
                    txTotal += parts.getOrElse(8) { "0" }
                        .toLongOrNull() ?: 0L
                }
            rxTotal to txTotal
        }.getOrElse {
            log.warn("Failed to read /proc/net/dev", it)
            0L to 0L
        }
    }

    private fun readDiskMetrics(): Pair<Long, Long> = runCatching {
        val fs = java.nio.file.Files.getFileStore(File("/").toPath())
        Pair(fs.totalSpace - fs.usableSpace, fs.totalSpace)
    }.getOrElse { Pair(0L, 0L) }

    private fun parseMemInfo(): Map<String, Long> = File("/proc/meminfo").readLines()
        .associate {
            val parts = it.split("\\s+".toRegex())
            parts[0].trimEnd(':') to (
                parts.getOrNull(1)
                    ?.toLongOrNull() ?: 0L
                )
        }
}

/** Resolved per-server input for a player-count probe: the internal port and PROXY-protocol flag. */
data class PlayerCountProbe(val internalListenPort: Int, val useProxyProtocol: Boolean)

/** Player count + sample parsed from `mc-monitor status --json`. */
internal data class PlayerStatus(val count: Int, val names: List<String>)

/**
 * Parses `mc-monitor status --json` → `server_info.players`. The sample key is capitalised
 * (`Sample`) because mc-monitor re-marshals the Go `Players.Sample` field, which has no json tag;
 * the raw server response uses lowercase `sample`, so both are accepted. A null/absent sample means
 * "no names". Returns null when the payload is not a valid status response.
 */
internal fun parseMcMonitorStatus(json: String): PlayerStatus? = runCatching {
    val players = Json.parseToJsonElement(json).jsonObject
        .get("server_info")?.jsonObject
        ?.get("players")?.jsonObject ?: return null
    val online = players["online"]?.jsonPrimitive?.intOrNull ?: return null
    val sample = players["Sample"] ?: players["sample"]
    val names = (sample as? JsonArray)
        ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
        ?: emptyList()
    PlayerStatus(online, names)
}.getOrNull()

/**
 * Normalizes host-core CPU consumption to a 0–100 percentage of what the container is allowed to use.
 *
 * Docker reports `100%` per fully-used core, so an unlimited container on an N-core host reads up to
 * `N * 100%`. This maps it onto the allocation instead:
 * - limit set: percentage of the cap (e.g. 1 core used of a 2-core cap → 50%)
 * - no limit: percentage of total host capacity (1 core used on a 4-core host → 25%)
 *
 * The denominator is clamped to [hostCores] so an over-allocated cap can still reach 100%, and the
 * result is clamped to 100 because cgroup quota-period granularity allows brief overshoot.
 */
internal fun normalizeCpuPercent(coresUsed: Double, hostCores: Int, cpuLimitMillicores: Int): Double {
    val host = hostCores.coerceAtLeast(1)
        .toDouble()
    val denomCores = if (cpuLimitMillicores > 0) minOf(cpuLimitMillicores / 1000.0, host) else host
    return (coresUsed / denomCores * 100.0).coerceIn(0.0, 100.0)
}
