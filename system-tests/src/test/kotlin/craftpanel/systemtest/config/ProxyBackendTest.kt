package craftpanel.systemtest.config

import craftpanel.systemtest.client.model.BackendInput
import craftpanel.systemtest.client.model.PutProxyBackendsRequest
import craftpanel.systemtest.client.model.CreateServerRequest
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

class ProxyBackendTest : BaseSystemTest() {

    private lateinit var proxyServerId: String
    private lateinit var gameServerId: String

    init {
        beforeSpec {
            proxyServerId = api.createServer(
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
            gameServerId = ServerHelper(api).createTestServer(nodeId)
        }

        afterSpec {
            runCatching { api.deleteServer(proxyServerId) }
            runCatching { api.deleteServer(gameServerId) }
        }

        context("Proxy backend management") {

            should("returns empty backends for a new proxy server") {
                val backends = api.getProxyBackends(proxyServerId)
                backends.backends.shouldBeEmpty()
            }

            should("replaces backends with a game server") {
                val result = api.replaceProxyBackends(
                    proxyServerId,
                    PutProxyBackendsRequest(
                        backends = listOf(
                            BackendInput(
                                backendServerId = gameServerId,
                                backendName = "Game Server 1",
                                order = 1
                            )
                        )
                    )
                )
                result.backends.shouldHaveSize(1)
                result.backends.first().backendServerId shouldBe gameServerId
                result.backends.first().backendName shouldBe "Game Server 1"
                result.backends.first().order shouldBe 1
            }

            should("reads back the configured backends") {
                val backends = api.getProxyBackends(proxyServerId)
                backends.backends.shouldHaveSize(1)
                backends.backends.first().backendServerId shouldBe gameServerId
            }

            should("replaces backends with multiple servers maintaining order") {
                val secondGame = ServerHelper(api).createTestServer(nodeId)
                try {
                    val result = api.replaceProxyBackends(
                        proxyServerId,
                        PutProxyBackendsRequest(
                            backends = listOf(
                                BackendInput(gameServerId, "Game 1", 1),
                                BackendInput(secondGame, "Game 2", 2)
                            )
                        )
                    )
                    result.backends.shouldHaveSize(2)
                    result.backends[0].order shouldBe 1
                    result.backends[1].order shouldBe 2
                } finally {
                    runCatching { api.deleteServer(secondGame) }
                }
            }

            should("replaces backends with empty list clears them") {
                api.replaceProxyBackends(
                    proxyServerId,
                    PutProxyBackendsRequest(backends = emptyList())
                )
                val backends = api.getProxyBackends(proxyServerId)
                backends.backends.shouldBeEmpty()
            }

            should("replacing backends on a non-proxy server returns 409") {
                val sId = ServerHelper(api).createTestServer(nodeId)
                try {
                    val ex = shouldThrow<ClientException> {
                        api.replaceProxyBackends(
                            sId,
                            PutProxyBackendsRequest(
                                backends = listOf(
                                    BackendInput(
                                        backendServerId = "00000000-0000-0000-0000-000000000000",
                                        backendName = "b1",
                                        order = 1
                                    )
                                )
                            )
                        )
                    }
                    ex.statusCode shouldBe 409
                } finally {
                    runCatching { api.deleteServer(sId) }
                }
            }

            should("getting backends on a non-existent server returns 404") {
                val ex = shouldThrow<ClientException> {
                    api.getProxyBackends("00000000-0000-0000-0000-000000000000")
                }
                ex.statusCode shouldBe 404
            }

            should("replacing backends with non-existent backend server returns 400") {
                val proxy = api.createServer(
                    CreateServerRequest(
                        name = "test-proxy-err-${System.currentTimeMillis()}",
                        nodeId = nodeId,
                        serverType = "VELOCITY",
                        mcVersion = "latest",
                        itzgImageTag = "latest",
                        memoryMb = 256,
                        cpuShares = 64
                    )
                )
                try {
                    val ex = shouldThrow<ClientException> {
                        api.replaceProxyBackends(
                            proxy.id,
                            PutProxyBackendsRequest(
                                backends = listOf(
                                    BackendInput(
                                        backendServerId = "00000000-0000-0000-0000-000000000000",
                                        backendName = "ghost",
                                        order = 1
                                    )
                                )
                            )
                        )
                    }
                    ex.statusCode shouldBe 422
                } finally {
                    runCatching { api.deleteServer(proxy.id) }
                }
            }

            should("starts proxy server after backends configured") {
                val helper = ServerHelper(api)
                api.replaceProxyBackends(
                    proxyServerId,
                    PutProxyBackendsRequest(
                        backends = listOf(
                            BackendInput(
                                backendServerId = gameServerId,
                                backendName = "Game Server 1",
                                order = 1
                            )
                        )
                    )
                )
                api.startServer(proxyServerId)
                helper.awaitStatus(proxyServerId, "HEALTHY")
            }
        }
    }
}
