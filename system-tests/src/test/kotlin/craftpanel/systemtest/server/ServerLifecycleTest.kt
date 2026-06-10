package craftpanel.systemtest.server

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.model.Frame
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as stringContain
import org.openapitools.client.infrastructure.ClientException

class ServerLifecycleTest : BaseSystemTest() {

    init {
        context("Server lifecycle") {

            context("creation") {
                val helper = ServerHelper(api)
                lateinit var serverId: String

                beforeEach {
                    serverId = helper.createTestServer(nodeId)
                }
                afterEach {
                    runCatching { api.deleteServer(serverId) }
                }

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
                        docker.inspectContainerCmd(containerName(serverId)).exec()
                    }
                }
            }

            context("start") {
                val helper = ServerHelper(api)
                lateinit var serverId: String

                beforeEach {
                    serverId = helper.createTestServer(nodeId)
                }
                afterEach {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }

                should("starting a STOPPED server transitions it to STARTING then HEALTHY") {
                    api.startServer(serverId)
                    val server = helper.awaitStatus(serverId, "HEALTHY")
                    server.status shouldBe "HEALTHY"
                }

                should("container exists on node after start") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                    val info = docker.inspectContainerCmd(containerName(serverId)).exec()
                    info.state?.running shouldBe true
                }

                should("container has correct env vars") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                    val info = docker.inspectContainerCmd(containerName(serverId)).exec()
                    val env = info.config?.env?.toList().orEmpty()
                    env shouldContain "TYPE=PAPER"
                    env shouldContain "VERSION=1.21.4"
                    env shouldContain "MEMORY=512M"
                }

                should("starting an already HEALTHY server returns 409") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                    val ex = shouldThrow<ClientException> { api.startServer(serverId) }
                    ex.statusCode shouldBe 409
                }
            }

            context("stop") {
                val helper = ServerHelper(api)
                lateinit var serverId: String

                beforeEach {
                    serverId = helper.createTestServer(nodeId)
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                    // Allow fake-server JVM to start its stdin reader before we stop it
                    helper.awaitContainerLog(containerName(serverId), "stdin listener ready", docker, 15_000)
                }
                afterEach {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }

                should("stopping a HEALTHY server transitions it to STOPPED") {
                    api.stopServer(serverId)
                    val server = helper.awaitStatus(serverId, "STOPPED")
                    server.status shouldBe "STOPPED"
                }

                should("stop command was sent to container stdin") {
                    api.stopServer(serverId)
                    helper.awaitStatus(serverId, "STOPPED")
                    val logs = docker.collectLogs(containerName(serverId))
                    logs stringContain "[fake-server] stdin received: stop"
                }

                should("stopping an already STOPPED server returns 409") {
                    api.stopServer(serverId)
                    helper.awaitStatus(serverId, "STOPPED")
                    val ex = shouldThrow<ClientException> { api.stopServer(serverId) }
                    ex.statusCode shouldBe 409
                }
            }

            context("delete") {
                val helper = ServerHelper(api)
                lateinit var serverId: String

                beforeEach {
                    serverId = helper.createTestServer(nodeId)
                }
                afterEach {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }

                should("deleting a STOPPED server returns 204") {
                    api.deleteServer(serverId)
                }

                should("deleted server no longer appears in GET /servers") {
                    api.deleteServer(serverId)
                    val servers = api.listServers()
                    servers.map { it.id } shouldNotContain serverId
                }

                should("deleting a RUNNING server returns 409") {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")
                    val ex = shouldThrow<ClientException> { api.deleteServer(serverId) }
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
