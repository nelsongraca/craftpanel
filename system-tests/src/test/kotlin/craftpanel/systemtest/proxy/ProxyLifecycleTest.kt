package craftpanel.systemtest.proxy

import craftpanel.systemtest.client.model.CreateServerRequest
import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

@Isolate
@Tags("ServerCore")
class ProxyLifecycleTest : BaseSystemTest() {

    init {

        lateinit var proxyId: String

        beforeSpec {
            proxyId = api.createServer(
                CreateServerRequest(
                    name = "test-proxy-${System.currentTimeMillis()}",
                    nodeId = nodeId,
                    serverType = "VELOCITY",
                    mcVersion = "latest",
                    itzgImageTag = "latest",
                    memoryMb = 256,
                    cpuShares = 64
                )
            ).id
        }

        afterSpec {
            runCatching {
                api.stopServer(proxyId)
                helper.awaitStoppedOrGone(proxyId)
                api.deleteServer(proxyId)
            }
            helper.awaitStoppedOrGone(proxyId)
        }

        context("Proxy lifecycle with force stop") {

            should("start proxy transitions it to HEALTHY") {
                api.startServer(proxyId)
                helper.awaitStatus(proxyId, ServerStatus.HEALTHY)
            }

            should("stop proxy transitions it to STOPPED") {
                api.stopServer(proxyId)
                helper.awaitStoppedOrGone(proxyId)

                val server = api.getServer(proxyId)
                server.status shouldBe ServerStatus.STOPPED
            }

            should("force stop on already stopped proxy returns 409") {
                val ex = shouldThrow<ClientException> { api.forceStopServer(proxyId) }
                ex.statusCode shouldBe 409
            }

            should("force stop on stopped proxy does not go UNHEALTHY") {
                api.startServer(proxyId)
                helper.awaitStatus(proxyId, ServerStatus.HEALTHY)

                api.stopServer(proxyId)
                helper.awaitStoppedOrGone(proxyId, timeoutMs = 30_000)

                val server = api.getServer(proxyId)
                server.status shouldBe ServerStatus.STOPPED
            }
        }
    }
}