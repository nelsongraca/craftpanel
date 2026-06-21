package craftpanel.systemtest.server

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.model.Frame
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Isolate
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException
import io.kotest.matchers.string.shouldContain as stringContain

@Isolate
class ServerLifecycleTest : BaseSystemTest() {

    init {


        context("Server lifecycle") {
            lateinit var serverId: String

            beforeContainer {
                serverId = helper.createTestServer(nodeId)
            }

            afterContainer {
                runCatching {
                    api.stopServer(serverId)
                    helper.awaitStatus(serverId, "STOPPED")
                    api.deleteServer(serverId)
                }
                helper.awaitStoppedOrGone(serverId)
            }
            context("creation") {

                should("creates a server and returns it with status STOPPED") {
                    val server = api.getServer(serverId)
                    server.status shouldBe "STOPPED"
                }

                should("created server appears in GET /servers list") {
                    val servers = api.listServers()
                    servers.map { it.id } shouldContain serverId
                }

                should("container does not exist on the node before first start") {
                    shouldThrow<NotFoundException> {
                        docker.inspectContainerCmd(containerName(serverId))
                            .exec()
                    }
                }
            }

            context("start") {

                should("starting a STOPPED server transitions it to STARTING then HEALTHY") {
                    api.startServer(serverId)
                    val server = helper.awaitStatus(serverId, "HEALTHY")
                    helper.awaitContainerLog(containerName(serverId), "stdin listener ready", docker, 15_000)
                    server.status shouldBe "HEALTHY"
                }

                should("container exists on node after start") {
                    val info = docker.inspectContainerCmd(containerName(serverId))
                        .exec()
                    info.state?.running shouldBe true
                }

                should("container has correct env vars") {
                    val info = docker.inspectContainerCmd(containerName(serverId))
                        .exec()
                    val env = info.config?.env?.toList()
                        .orEmpty()
                    env shouldContain "TYPE=PAPER"
                    env shouldContain "VERSION=1.21.4"
                    env shouldContain "MEMORY=512M"
                }

                should("starting an already HEALTHY server returns 409") {
                    val ex = shouldThrow<ClientException> { api.startServer(serverId) }
                    ex.statusCode shouldBe 409
                }
            }

            context("delete") {

                should("deleting a RUNNING server returns 409") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                    val ex = shouldThrow<ClientException> { api.deleteServer(serverId) }
                    ex.statusCode shouldBe 409
                }

                should("deleting a STOPPED server returns 204") {
                    api.stopServer(serverId)
                    helper.awaitStoppedOrGone(serverId)
                    api.deleteServer(serverId)
                }

                should("deleted server no longer appears in GET /servers") {
                    val servers = api.listServers()
                    servers.map { it.id } shouldNotContain serverId
                }

            }

            context("stop") {

                should("stopping a HEALTHY server transitions it to STOPPED") {
                    api.startServer(serverId)
                    val response = helper.awaitStatus(serverId, "HEALTHY")
                    response.status shouldBe "HEALTHY"
                    api.stopServer(serverId)
                    helper.awaitStoppedOrGone(serverId)
                    val server = api.getServer(serverId)
                    server.status shouldBe "STOPPED"
                }

                should("stop command was sent to container stdin") {
                    val logs = docker.collectLogs(containerName(serverId))
                    logs stringContain "[fake-server] stdin received: stop"
                }

                should("stopping an already STOPPED server returns 409") {
                    val ex = shouldThrow<ClientException> { api.stopServer(serverId) }
                    ex.statusCode shouldBe 409
                }
            }
        }
    }
}

private fun DockerClient.collectLogs(containerName: String): String {
    val sb = StringBuilder()
    logContainerCmd(containerName)
        .withStdOut(true)
        .withStdErr(true)
        .exec(object : ResultCallback.Adapter<Frame>() {
            override fun onNext(frame: Frame) {
                sb.append(String(frame.payload))
            }
        })
        .awaitCompletion()
    return sb.toString()
}
