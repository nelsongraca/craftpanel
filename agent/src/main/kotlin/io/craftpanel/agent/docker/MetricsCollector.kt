package io.craftpanel.agent.docker

import com.craftpanel.agent.v1.*
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.Statistics
import com.google.protobuf.timestamp
import org.slf4j.LoggerFactory
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

    fun collectContainerMetrics(serverId: String, containerId: String): ContainerMetricsUpdate? {
        return runCatching {
            val latch = CountDownLatch(1)
            var captured: Statistics? = null

            val callback = object : ResultCallback<Statistics> {
                override fun onStart(c: Closeable?) {}
                override fun onNext(s: Statistics) {
                    captured = s; latch.countDown()
                }

                override fun onError(t: Throwable) {
                    log.warn("Stats error for $containerId: ${t.message}"); latch.countDown()
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
            val numCpus = (cpu.onlineCpus?.toInt()
                ?.takeIf { it > 0 }
                ?: cpu.cpuUsage?.percpuUsage?.size?.takeIf { it > 0 } ?: 1)
            val cpuPct = if (systemDelta > 0) (cpuDelta.toDouble() / systemDelta) * numCpus * 100.0 else 0.0

            val statsConfig = mem.stats
            val cache = statsConfig?.cache ?: statsConfig?.inactiveFile ?: 0L
            val ramUsedMb = ((mem.usage ?: 0L) - cache).coerceAtLeast(0L) / (1024 * 1024)

            val now = Instant.now()
            containerMetricsUpdate {
                this.serverId = serverId
                recordedAt = timestamp { seconds = now.epochSecond; nanos = now.nano }
                this.cpuPercent = cpuPct
                this.ramUsedMb = ramUsedMb.toInt()
                netInBytes = 0L
                netOutBytes = 0L
            }
        }.getOrElse {
            log.warn("Failed to collect container metrics for $containerId", it)
            null
        }
    }

    fun collectPlayerCount(serverId: String, containerId: String): PlayerUpdate? {
        return runCatching {
            val exec = docker.execCreateCmd(containerId)
                .withCmd("rcon-cli", "list")
                .withAttachStdout(true)
                .withAttachStderr(false)
                .exec()
            val output = StringBuilder()
            val latch = CountDownLatch(1)
            docker.execStartCmd(exec.id)
                .withDetach(false)
                .exec(object : ResultCallback.Adapter<Frame>() {
                    override fun onNext(frame: Frame) {
                        frame.payload?.let { output.append(String(it)) }
                    }

                    override fun onComplete() {
                        latch.countDown()
                    }

                    override fun onError(t: Throwable) {
                        latch.countDown()
                    }
                })
            if (!latch.await(5, TimeUnit.SECONDS)) return null
            parsePlayerList(serverId,
                output.toString()
                    .trim()
            )
        }.getOrElse {
            log.warn("Failed to collect player count for $containerId: ${it.message}")
            null
        }
    }

    private fun parsePlayerList(serverId: String, output: String): PlayerUpdate? {
        val countMatch = Regex("""There are (\d+)""").find(output) ?: return null
        val count = countMatch.groupValues[1].toInt()
        val names = if (count > 0 && output.contains(": ")) {
            output.substringAfter(": ")
                .trim()
                .split(", ")
                .filter { it.isNotBlank() }
        }
        else emptyList()
        val now = Instant.now()
        return playerUpdate {
            this.serverId = serverId
            playerCount = count
            playerNames.addAll(names)
            recordedAt = timestamp { seconds = now.epochSecond; nanos = now.nano }
        }
    }

    fun collectCapacity(): Pair<Int, Int> {
        val totalRamMb = runCatching {
            ((parseMemInfo()["MemTotal"] ?: 0L) / 1024).toInt()
        }.getOrElse { 0 }
        val totalCpuShares = Runtime.getRuntime()
            .availableProcessors() * 1024
        return Pair(totalRamMb, totalCpuShares)
    }

    private data class ProcMetrics(
        val cpuPercent: Double,
        val cpuPerCore: List<Double>,
        val ramUsedMb: Int,
        val ramTotalMb: Int,
    )

    private fun readProcMetrics(): ProcMetrics {
        return runCatching {
            val memInfo = parseMemInfo()
            val totalKb = memInfo["MemTotal"] ?: 0L
            val availKb = memInfo["MemAvailable"] ?: 0L
            val (cpuPercent, cpuPerCore) = readCpuPercent()
            ProcMetrics(
                cpuPercent = cpuPercent,
                cpuPerCore = cpuPerCore,
                ramUsedMb = ((totalKb - availKb) / 1024).toInt(),
                ramTotalMb = (totalKb / 1024).toInt(),
            )
        }.getOrElse {
            log.warn("Failed to read /proc metrics", it)
            ProcMetrics(0.0, emptyList(), 0, 0)
        }
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
            if (pc == null || ct - pc.total <= 0) 0.0
            else (1.0 - (ci - pc.idle).toDouble() / (ct - pc.total)) * 100.0
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
                    if (iface == "lo") return@forEach
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

    private fun readDiskMetrics(): Pair<Long, Long> {
        return runCatching {
            val fs = File("/").toPath().fileSystem.getFileStores()
                .first()
            Pair(fs.totalSpace - fs.usableSpace, fs.totalSpace)
        }.getOrElse { Pair(0L, 0L) }
    }

    private fun parseMemInfo(): Map<String, Long> =
        File("/proc/meminfo").readLines()
            .associate {
                val parts = it.split("\\s+".toRegex())
                parts[0].trimEnd(':') to (parts.getOrNull(1)
                    ?.toLongOrNull() ?: 0L)
            }
}
