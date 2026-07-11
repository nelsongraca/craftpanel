package io.craftpanel.agent.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.AccessMode
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Ports
import com.github.dockerjava.api.model.RestartPolicy
import com.github.dockerjava.api.model.Volume
import org.slf4j.LoggerFactory
import java.io.File
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RsyncMigrator(private val docker: DockerClient, private val craftpanelNetwork: String = "", private val containerNamePrefix: String = "craftpanel") {

    private val log = LoggerFactory.getLogger(RsyncMigrator::class.java)

    fun startReceiver(migrationId: String, port: Int, destPath: String, password: String, rsyncImage: String): String {
        File(destPath).mkdirs()
        val containerName = "$containerNamePrefix-rsync-recv-$migrationId"
        val portBinding = ExposedPort.tcp(port)
        val portBindings = Ports()
        portBindings.bind(portBinding, Ports.Binding.bindPort(port))

        val script = """
            set -e
            apk add rsync --quiet --no-progress
            mkdir -p /etc/rsyncd
            cat > /etc/rsyncd.conf << 'CONF'
[data]
path = /data
read only = no
auth users = craftpanel
secrets file = /etc/rsyncd/secrets
CONF
            echo "craftpanel:$password" > /etc/rsyncd/secrets  # password is alphanumeric-only — safe for unquoted rsyncd secrets file
            chmod 600 /etc/rsyncd/secrets
            rsync --daemon --no-detach --port $port --config /etc/rsyncd.conf
        """.trimIndent()

        val hostConfig = HostConfig.newHostConfig()
            .withPortBindings(portBindings)
            .withBinds(Bind(destPath, Volume("/data"), AccessMode.rw))
            .withRestartPolicy(RestartPolicy.noRestart())
            .let { hc -> if (craftpanelNetwork.isNotBlank()) hc.withNetworkMode(craftpanelNetwork) else hc }

        docker.createContainerCmd(rsyncImage)
            .withName(containerName)
            .withCmd("sh", "-c", script)
            .withExposedPorts(portBinding)
            .withHostConfig(hostConfig)
            .exec()
        docker.startContainerCmd(containerName)
            .exec()
        // Wait for rsyncd to be accepting connections before declaring ready.
        // The container needs to run `apk add rsync` first, so startup can take several seconds.
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching {
                    Socket("127.0.0.1", port)
                        .close()
                }.isSuccess
            ) {
                break
            }
            Thread.sleep(500)
        }
        log.info("Started rsyncd container $containerName on port $port")
        return containerName
    }

    fun runTransfer(
        migrationId: String,
        sourcePath: String,
        destIp: String,
        destPort: Int,
        password: String,
        isFinalPass: Boolean,
        rsyncImage: String,
        onProgress: (bytesTransferred: Long, totalBytes: Long, percent: Int, phase: String) -> Unit
    ): Boolean {
        val containerName = "$containerNamePrefix-rsync-send-${migrationId}${if (isFinalPass) "-final" else ""}"
        val script = """
            set -e
            apk add rsync --quiet --no-progress
            rsync -az --progress --stats /source/ rsync://craftpanel@$destIp:$destPort/data/
        """.trimIndent()

        File(sourcePath).mkdirs()
        val hostConfig = HostConfig.newHostConfig()
            .withBinds(Bind(sourcePath, Volume("/source"), AccessMode.ro))
            .withRestartPolicy(RestartPolicy.noRestart())
            .let { hc -> if (craftpanelNetwork.isNotBlank()) hc.withNetworkMode(craftpanelNetwork) else hc }

        docker.createContainerCmd(rsyncImage)
            .withName(containerName)
            .withCmd("sh", "-c", script)
            .withEnv("RSYNC_PASSWORD=$password")
            .withHostConfig(hostConfig)
            .exec()
        docker.startContainerCmd(containerName)
            .exec()

        val latch = CountDownLatch(1)
        val outputBuf = StringBuilder()
        docker.logContainerCmd(containerName)
            .withStdOut(true)
            .withStdErr(true)
            .withFollowStream(true)
            .exec(object : ResultCallback.Adapter<Frame>() {
                override fun onNext(frame: Frame) {
                    val line = String(frame.payload).trim()
                    outputBuf.appendLine(line)
                    parseRsyncProgress(line)?.let { p ->
                        onProgress(p.bytes, p.total, p.percent, p.phase)
                    }
                }

                override fun onComplete() = latch.countDown()
                override fun onError(t: Throwable) = latch.countDown()
            })

        latch.await(4, TimeUnit.HOURS)

        val exitCode = runCatching {
            docker.inspectContainerCmd(containerName)
                .exec().state?.exitCodeLong ?: 1
        }.getOrDefault(1L)

        runCatching {
            docker.removeContainerCmd(containerName)
                .withForce(true)
                .exec()
        }
        return exitCode == 0L
    }

    internal data class RsyncProgress(val bytes: Long, val total: Long, val percent: Int, val phase: String)

    // ponytail: parser ceiling — total is always 0 (rsync --progress lines don't carry a
    // running total), phase is always the constant "transferring", and --stats summary lines
    // (e.g. "Total transferred file size") are ignored entirely. Upgrade path: parse the
    // rsync --stats summary block emitted at end-of-transfer for a real total/phase signal.
    internal fun parseRsyncProgress(line: String): RsyncProgress? {
        val progressRegex = Regex("""^\s*([\d,]+)\s+(\d+)%""")
        val match = progressRegex.find(line) ?: return null
        val bytes = match.groupValues[1].replace(",", "")
            .toLongOrNull() ?: return null
        val pct = match.groupValues[2].toIntOrNull() ?: return null
        return RsyncProgress(bytes, 0L, pct, "transferring")
    }
}
