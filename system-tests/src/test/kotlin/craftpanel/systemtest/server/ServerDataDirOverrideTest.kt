package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.client.model.UpdateServerDataDirRequest
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.SharedStack
import craftpanel.systemtest.harness.pollUntilNotNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.openapitools.client.infrastructure.ClientException
import java.io.File

/**
 * End-to-end coverage for the `server.dir_override` admin override: the agent must place the
 * data directory (and the container's bind mount) at `servers/<override>` instead of
 * `servers/<serverId>`, including after a live change to a running server.
 */
@Isolate
@Tags("ServerCore")
class ServerDataDirOverrideTest : BaseSystemTest() {

    private lateinit var serverId: String

    private fun dataMounts(): List<String> = docker.inspectContainerCmd(containerName(serverId)).exec()
        .mounts.orEmpty()
        .filter { it.destination?.path == "/data" }
        .mapNotNull { it.source }

    private suspend fun ensureRunning() {
        if (api.getServer(serverId).status != ServerStatus.HEALTHY) api.startServer(serverId)
        helper.awaitStatus(serverId, ServerStatus.HEALTHY)
    }

    /** Inspect that tolerates the container being briefly absent (mid-recreate). */
    private fun inspectOrNull() = runCatching { docker.inspectContainerCmd(containerName(serverId)).exec() }.getOrNull()

    init {

        beforeSpec {
            nodeHelper.pollUntilActive(nodeId)
            serverId = helper.createTestServer(nodeId)
        }

        afterSpec {
            if (::serverId.isInitialized) {
                runCatching {
                    api.stopServer(serverId)
                    helper.awaitStatus(serverId, ServerStatus.STOPPED, 30_000)
                    api.deleteServer(serverId)
                }
                helper.awaitStoppedOrGone(serverId)
            }
        }

        context("Server data directory override") {

            should("mount the container at servers/<override> and land data there") {
                val overrideName = "cp-override-${System.currentTimeMillis()}"
                api.updateServerDataDir(serverId, UpdateServerDataDirRequest(dataDirName = overrideName))
                api.getServer(serverId).dataDirName shouldBe overrideName

                ensureRunning()

                dataMounts().any { it.endsWith("/servers/$overrideName") } shouldBe true

                // A write through the agent resolves the same directory as the container mount.
                helper.uploadFile(serverId, "/override-marker.txt", "marker".toByteArray(), authHelper.token)
                val marker = pollUntilNotNull(15_000) {
                    SharedStack.agentDataDirs()
                        .firstOrNull { File(it, "servers/$overrideName/override-marker.txt").exists() }
                }
                (marker != null) shouldBe true

                // The default server-id directory must not have received the write.
                SharedStack.agentDataDirs().none { File(it, "servers/$serverId/override-marker.txt").exists() } shouldBe true
            }

            should("return 422 for an invalid directory name") {
                shouldThrow<ClientException> {
                    api.updateServerDataDir(serverId, UpdateServerDataDirRequest(dataDirName = "../etc"))
                }.statusCode shouldBe 422
            }

            should("recreate a running server onto the new directory when the override changes") {
                ensureRunning()
                val beforeId = docker.inspectContainerCmd(containerName(serverId)).exec().id

                val runtimeName = "cp-runtime-${System.currentTimeMillis()}"
                api.updateServerDataDir(serverId, UpdateServerDataDirRequest(dataDirName = runtimeName))

                val recreated = pollUntilNotNull(30_000) {
                    inspectOrNull()
                        ?.takeIf { info ->
                            info.id != beforeId &&
                                info.state?.running == true &&
                                info.mounts.orEmpty().any { it.source?.endsWith("/servers/$runtimeName") == true }
                        }
                }
                (recreated != null) shouldBe true
            }

            should("clear the override and remount at the server-id directory") {
                api.updateServerDataDir(
                    serverId,
                    UpdateServerDataDirRequest(dataDirName = "cp-cleared-${System.currentTimeMillis()}")
                )
                api.getServer(serverId).dataDirName shouldNotBe null

                api.updateServerDataDir(serverId, UpdateServerDataDirRequest(dataDirName = null))
                api.getServer(serverId).dataDirName shouldBe null

                val remounted = pollUntilNotNull(30_000) {
                    inspectOrNull()
                        ?.takeIf { info ->
                            info.state?.running == true &&
                                info.mounts.orEmpty().any { it.source?.endsWith("/servers/$serverId") == true }
                        }
                }
                (remounted != null) shouldBe true
            }
        }
    }
}
