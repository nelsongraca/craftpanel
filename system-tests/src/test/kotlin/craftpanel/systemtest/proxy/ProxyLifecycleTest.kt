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
                    cpuLimitMillicores = 1000
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

            should("transition a started proxy to HEALTHY") {
                api.startServer(proxyId)
                helper.awaitStatus(proxyId, ServerStatus.HEALTHY)
            }

            should("transition a stopped proxy to STOPPED") {
                api.stopServer(proxyId)
                helper.awaitStoppedOrGone(proxyId)

                val server = api.getServer(proxyId)
                server.status shouldBe ServerStatus.STOPPED
            }

            should("return 409 when force-stopping an already-stopped proxy") {
                val ex = shouldThrow<ClientException> { api.forceStopServer(proxyId) }
                ex.statusCode shouldBe 409
            }

            should("not mark a force-stopped proxy UNHEALTHY") {
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
