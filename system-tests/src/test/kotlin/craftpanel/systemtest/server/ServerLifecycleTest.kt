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
        lateinit var creationServerId: String
        lateinit var startServerId: String
        lateinit var stopServerId: String
        lateinit var deleteServerId1: String
        lateinit var deleteServerId2: String
        lateinit var deleteServerId3: String
        val helper = ServerHelper(api)
        val startHelper = ServerHelper(api)
        val stopHelper = ServerHelper(api)
        val deleteHelper = ServerHelper(api)

        beforeSpec {
            creationServerId = helper.createTestServer(nodeId)
            startServerId = startHelper.createTestServer(nodeId)
            stopServerId = stopHelper.createTestServer(nodeId)
            deleteServerId1 = deleteHelper.createTestServer(nodeId)
            deleteServerId2 = deleteHelper.createTestServer(nodeId)
            deleteServerId3 = deleteHelper.createTestServer(nodeId)
        }

        afterSpec {
            runCatching { api.stopServer(deleteServerId3) }
            deleteHelper.awaitStoppedOrGone(deleteServerId3)
            listOf(creationServerId, startServerId, stopServerId, deleteServerId1, deleteServerId2, deleteServerId3).forEach {
                runCatching { api.deleteServer(it) }
            }
        }

        context("Server lifecycle") {

            context("creation") {

                should("creates a server and returns it with status STOPPED") {
                    val server = api.getServer(creationServerId)
                    server.status shouldBe "STOPPED"
                }

                should("created server appears in GET /servers list") {
                    val servers = api.listServers()
                    servers.map { it.id } shouldContain creationServerId
                }

                should("container does not exist on the node before first start") {
                    shouldThrow<NotFoundException> {
                        docker.inspectContainerCmd(containerName(creationServerId))
                            .exec()
                    }
                }
            }

            context("start") {

                afterEach {
                    runCatching { api.stopServer(startServerId) }
                    startHelper.awaitStoppedOrGone(startServerId)
                }

                should("starting a STOPPED server transitions it to STARTING then HEALTHY") {
                    api.startServer(startServerId)
                    val server = startHelper.awaitStatus(startServerId, "HEALTHY")
                    server.status shouldBe "HEALTHY"
                }

                should("container exists on node after start") {
                    api.startServer(startServerId)
                    startHelper.awaitStatus(startServerId, "HEALTHY")
                    val info = docker.inspectContainerCmd(containerName(startServerId))
                        .exec()
                    info.state?.running shouldBe true
                }

                should("container has correct env vars") {
                    api.startServer(startServerId)
                    startHelper.awaitStatus(startServerId, "HEALTHY")
                    val info = docker.inspectContainerCmd(containerName(startServerId))
                        .exec()
                    val env = info.config?.env?.toList()
                        .orEmpty()
                    env shouldContain "TYPE=PAPER"
                    env shouldContain "VERSION=1.21.4"
                    env shouldContain "MEMORY=512M"
                }

                should("starting an already HEALTHY server returns 409") {
                    api.startServer(startServerId)
                    startHelper.awaitStatus(startServerId, "HEALTHY")
                    val ex = shouldThrow<ClientException> { api.startServer(startServerId) }
                    ex.statusCode shouldBe 409
                }
            }

            context("stop") {

                beforeEach {
                    api.startServer(stopServerId)
                    stopHelper.awaitStatus(stopServerId, "HEALTHY")
                    stopHelper.awaitContainerLog(containerName(stopServerId), "stdin listener ready", docker, 15_000)
                }

                afterEach {
                    runCatching { api.stopServer(stopServerId) }
                    stopHelper.awaitStoppedOrGone(stopServerId)
                }

                should("stopping a HEALTHY server transitions it to STOPPED") {
                    api.stopServer(stopServerId)
                    val server = stopHelper.awaitStatus(stopServerId, "STOPPED")
                    server.status shouldBe "STOPPED"
                }

                should("stop command was sent to container stdin") {
                    api.stopServer(stopServerId)
                    stopHelper.awaitStatus(stopServerId, "STOPPED")
                    val logs = docker.collectLogs(containerName(stopServerId))
                    logs stringContain "[fake-server] stdin received: stop"
                }

                should("stopping an already STOPPED server returns 409") {
                    api.stopServer(stopServerId)
                    stopHelper.awaitStatus(stopServerId, "STOPPED")
                    val ex = shouldThrow<ClientException> { api.stopServer(stopServerId) }
                    ex.statusCode shouldBe 409
                }
            }

            context("delete") {

                should("deleting a STOPPED server returns 204") {
                    api.deleteServer(deleteServerId1)
                }

                should("deleted server no longer appears in GET /servers") {
                    api.deleteServer(deleteServerId2)
                    val servers = api.listServers()
                    servers.map { it.id } shouldNotContain deleteServerId2
                }

                should("deleting a RUNNING server returns 409") {
                    api.startServer(deleteServerId3)
                    deleteHelper.awaitStatus(deleteServerId3, "HEALTHY")
                    val ex = shouldThrow<ClientException> { api.deleteServer(deleteServerId3) }
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
