package craftpanel.systemtest.server

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.model.Frame
import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException
import io.kotest.matchers.string.shouldContain as stringContain

@Isolate
@Tags("ServerCore")
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
                    helper.awaitStatus(serverId, ServerStatus.STOPPED)
                    api.deleteServer(serverId)
                }
                helper.awaitStoppedOrGone(serverId)
            }
            context("creation") {

                should("create a server and return it with status STOPPED") {
                    val server = api.getServer(serverId)
                    server.status shouldBe ServerStatus.STOPPED
                }

                should("show a created server in the GET /servers list") {
                    val servers = api.listServers()
                    servers.map { it.id } shouldContain serverId
                }

                should("keep the container absent on the node before the first start") {
                    shouldThrow<NotFoundException> {
                        docker.inspectContainerCmd(containerName(serverId))
                            .exec()
                    }
                }
            }

            context("start") {

                should("start a STOPPED server and transition it to STARTING then HEALTHY") {
                    api.startServer(serverId)
                    val server = helper.awaitStatus(serverId, ServerStatus.HEALTHY)
                    helper.awaitContainerLog(containerName(serverId), "stdin listener ready", docker, 15_000)
                    server.status shouldBe ServerStatus.HEALTHY
                }

                should("create the container on the node after start") {
                    val info = docker.inspectContainerCmd(containerName(serverId))
                        .exec()
                    info.state?.running shouldBe true
                }

                should("set the correct env vars on the container") {
                    val info = docker.inspectContainerCmd(containerName(serverId))
                        .exec()
                    val env = info.config?.env?.toList()
                        .orEmpty()
                    env shouldContain "TYPE=PAPER"
                    env shouldContain "VERSION=1.21.4"
                    env shouldContain "MEMORY=256M"
                }

                should("return 409 when starting an already-HEALTHY server") {
                    val ex = shouldThrow<ClientException> { api.startServer(serverId) }
                    ex.statusCode shouldBe 409
                }
            }

            context("delete") {

                should("return 409 when deleting a RUNNING server") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, ServerStatus.HEALTHY)
                    val ex = shouldThrow<ClientException> { api.deleteServer(serverId) }
                    ex.statusCode shouldBe 409
                }

                should("return 204 when deleting a STOPPED server") {
                    api.stopServer(serverId)
                    helper.awaitStoppedOrGone(serverId)
                    api.deleteServer(serverId)
                }

                should("omit a deleted server from GET /servers") {
                    val servers = api.listServers()
                    servers.map { it.id } shouldNotContain serverId
                }
            }

            context("stop") {

                should("stop a HEALTHY server and transition it to STOPPED") {
                    api.startServer(serverId)
                    val response = helper.awaitStatus(serverId, ServerStatus.HEALTHY)
                    response.status shouldBe ServerStatus.HEALTHY
                    api.stopServer(serverId)
                    helper.awaitStoppedOrGone(serverId)
                    val server = api.getServer(serverId)
                    server.status shouldBe ServerStatus.STOPPED
                }

                should("send the stop command to container stdin") {
                    val logs = docker.collectLogs(containerName(serverId))
                    logs stringContain "[fake-server] stdin received: stop"
                }

                should("return 409 when stopping an already-STOPPED server") {
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
