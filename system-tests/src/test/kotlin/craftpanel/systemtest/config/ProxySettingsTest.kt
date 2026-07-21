package craftpanel.systemtest.config

import craftpanel.systemtest.client.model.*
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.openapitools.client.infrastructure.ClientException
import org.tomlj.Toml

@Tags("Misc")
class ProxySettingsTest : BaseSystemTest() {

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

        context("Proxy settings management") {

            should("returns null settings for a new proxy server") {
                val settings = api.getProxySettings(proxyServerId)
                settings.motd shouldBe null
                settings.maxPlayers shouldBe null
                settings.forwardingMode shouldBe null
            }

            should("sets and returns proxy settings") {
                val result = api.updateProxySettings(
                    proxyServerId,
                    UpdateProxySettingsRequest(
                        motd = "Welcome to the Proxy",
                        maxPlayers = 50,
                        forwardingMode = "legacy"
                    )
                )
                result.motd shouldBe "Welcome to the Proxy"
                result.maxPlayers shouldBe 50
                result.forwardingMode shouldBe "LEGACY"

                val settings = api.getProxySettings(proxyServerId)
                settings.motd shouldBe "Welcome to the Proxy"
                settings.maxPlayers shouldBe 50
                settings.forwardingMode shouldBe "LEGACY"
            }

            should("clears settings by setting null values") {
                api.updateProxySettings(
                    proxyServerId,
                    UpdateProxySettingsRequest(
                        motd = null,
                        maxPlayers = null,
                        forwardingMode = null
                    )
                )
                val settings = api.getProxySettings(proxyServerId)
                settings.motd shouldBe null
                settings.maxPlayers shouldBe null
                settings.forwardingMode shouldBe null
            }

            should("rejects invalid forwarding mode with 422") {
                val ex = shouldThrow<ClientException> {
                    api.updateProxySettings(
                        proxyServerId,
                        UpdateProxySettingsRequest(
                            motd = null,
                            maxPlayers = null,
                            forwardingMode = "bogus"
                        )
                    )
                }
                ex.statusCode shouldBe 422
            }

            should("rejects non-positive maxPlayers with 422") {
                val ex = shouldThrow<ClientException> {
                    api.updateProxySettings(
                        proxyServerId,
                        UpdateProxySettingsRequest(
                            motd = null,
                            maxPlayers = 0,
                            forwardingMode = null
                        )
                    )
                }
                ex.statusCode shouldBe 422
            }

            should("updateProxySettings on a non-proxy server returns 409") {
                val ex = shouldThrow<ClientException> {
                    api.updateProxySettings(
                        gameServerId,
                        UpdateProxySettingsRequest(
                            motd = "x",
                            maxPlayers = 10,
                            forwardingMode = "legacy"
                        )
                    )
                }
                ex.statusCode shouldBe 409
            }

            should("getProxySettings on a non-existent server returns 404") {
                val ex = shouldThrow<ClientException> {
                    api.getProxySettings("00000000-0000-0000-0000-000000000000")
                }
                ex.statusCode shouldBe 404
            }

            should("starts proxy with PATCH_DEFINITIONS env var") {
                api.updateProxySettings(
                    proxyServerId,
                    UpdateProxySettingsRequest(
                        motd = "My Proxy",
                        maxPlayers = 20,
                        forwardingMode = "legacy"
                    )
                )
                api.replaceProxyBackends(
                    proxyServerId,
                    PutProxyBackendsRequest(
                        backends = listOf(
                            BackendInput(
                                backendServerId = gameServerId,
                                backendName = "game-server-1",
                                order = 1
                            )
                        )
                    )
                )
                api.startServer(proxyServerId)
                helper.awaitStatus(proxyServerId, ServerStatus.HEALTHY)

                val info = docker.inspectContainerCmd(containerName(proxyServerId)).exec()
                val env = info.config?.env?.toList().orEmpty()
                env shouldContain "PATCH_DEFINITIONS=/server/craftpanel-patch.json"

                // fake-proxy is a stub — it never runs the real itzg/mc-proxy entrypoint (no
                // default velocity.toml download, no mc-image-helper invocation). Seed a default
                // config with the same top-level keys the real image ships (verified against a
                // real itzg/mc-proxy container) and run the real mc-image-helper binary (baked
                // into the fake-proxy image, see fake-server/Dockerfile) against the patch master
                // already wrote — proving the PatchSet JSON is genuinely parseable, not just
                // shaped the way our own renderer/test expect.
                val proxyContainer = containerName(proxyServerId)
                val seedVelocityToml = """
                    motd = "A Velocity Server"
                    show-max-players = 500
                    player-info-forwarding-mode = "NONE"

                    [servers]
                    try = []
                """.trimIndent()
                execInContainer(proxyContainer, "sh", "-c", "cat > /server/velocity.toml <<'EOF'\n$seedVelocityToml\nEOF")
                execInContainer(proxyContainer, "mc-image-helper", "patch", "/server/craftpanel-patch.json")

                val tomlText = execInContainer(proxyContainer, "cat", "/server/velocity.toml")
                val toml = Toml.parse(tomlText)
                toml.hasErrors() shouldBe false

                val servers = toml.getTable("servers")!!
                servers.keySet() shouldContainExactly setOf("game-server-1", "try")
                servers.getString("game-server-1") shouldBe "${containerName(gameServerId)}:25565"
                servers.getArray("try")!!.toList() shouldContainExactly listOf("game-server-1")
            }
        }
    }
}
