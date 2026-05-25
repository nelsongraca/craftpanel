package io.craftpanel.agent.docker

import com.craftpanel.agent.v1.NodeMetricsUpdate
import com.craftpanel.agent.v1.nodeMetricsUpdate
import com.github.dockerjava.api.DockerClient
import com.google.protobuf.timestamp
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

class MetricsCollector(private val docker: DockerClient) {
    private val log = LoggerFactory.getLogger(MetricsCollector::class.java)

    fun collect(): NodeMetricsUpdate {
        val now = Instant.now()
        val (cpuPercent, ramUsedMb, ramTotalMb) = readProcMetrics()
        val (diskUsed, diskTotal) = readDiskMetrics()

        return nodeMetricsUpdate {
            recordedAt = timestamp {
                seconds = now.epochSecond
                nanos = now.nano
            }
            this.cpuPercent = cpuPercent
            this.ramUsedMb = ramUsedMb
            this.ramTotalMb = ramTotalMb
            netInBytes = 0L    // TODO: read from /proc/net/dev
            netOutBytes = 0L
            diskUsedBytes = diskUsed
            diskTotalBytes = diskTotal
        }
    }

    private data class ProcMetrics(val cpuPercent: Double, val ramUsedMb: Int, val ramTotalMb: Int)

    private fun readProcMetrics(): ProcMetrics {
        return runCatching {
            val memInfo = File("/proc/meminfo").readLines()
                .associate {
                    val parts = it.split("\\s+".toRegex())
                    parts[0].trimEnd(':') to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
                }

            val totalKb = memInfo["MemTotal"] ?: 0L
            val availKb = memInfo["MemAvailable"] ?: 0L
            val usedKb = totalKb - availKb

            ProcMetrics(
                cpuPercent = 0.0,  // TODO: compute delta between two /proc/stat reads
                ramUsedMb = (usedKb / 1024).toInt(),
                ramTotalMb = (totalKb / 1024).toInt(),
            )
        }.getOrElse {
            log.warn("Failed to read /proc/meminfo", it)
            ProcMetrics(0.0, 0, 0)
        }
    }

    private fun readDiskMetrics(): Pair<Long, Long> {
        return runCatching {
            val fs = File("/").toPath().fileSystem.getFileStores().first()
            Pair(fs.totalSpace - fs.usableSpace, fs.totalSpace)
        }.getOrElse { Pair(0L, 0L) }
    }
}
