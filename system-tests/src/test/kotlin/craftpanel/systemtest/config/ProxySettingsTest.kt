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
import io.kotest.matchers.string.shouldContain
import org.openapitools.client.infrastructure.ClientException
import org.tomlj.Toml

@Tags("Misc")
class ProxySettingsTest : BaseSystemTest() {

    private lateinit var proxyServerId: String
    private lateinit var bungeeProxyId: String
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
                    cpuLimitMillicores = 1000
                )
            ).id
            bungeeProxyId = api.createServer(
                CreateServerRequest(
                    name = "test-bungee-${System.currentTimeMillis()}",
                    nodeId = nodeId,
                    serverType = "BUNGEECORD",
                    mcVersion = "latest",
                    itzgImageTag = "latest",
                    memoryMb = 256,
                    cpuLimitMillicores = 1000
                )
            ).id
            gameServerId = ServerHelper(api).createTestServer(nodeId)
        }

        afterSpec {
            runCatching { api.deleteServer(proxyServerId) }
            runCatching { api.deleteServer(bungeeProxyId) }
            runCatching { api.deleteServer(gameServerId) }
        }

        context("Proxy settings management") {

            should("return default motd and null settings for a new proxy server") {
                val settings = api.getProxySettings(proxyServerId)
                settings.motd shouldBe "Velocity powered by CraftPanel"
                settings.maxPlayers shouldBe null
                settings.forwardingMode shouldBe null
                settings.proxyProtocol shouldBe false
            }

            should("set and return proxy settings") {
                val result = api.updateProxySettings(
                    proxyServerId,
                    UpdateProxySettingsRequest(
                        motd = "Welcome to the Proxy",
                        maxPlayers = 50,
                        forwardingMode = "legacy",
                        proxyProtocol = true
                    )
                )
                result.motd shouldBe "Welcome to the Proxy"
                result.maxPlayers shouldBe 50
                result.forwardingMode shouldBe "LEGACY"
                result.proxyProtocol shouldBe true

                val settings = api.getProxySettings(proxyServerId)
                settings.motd shouldBe "Welcome to the Proxy"
                settings.maxPlayers shouldBe 50
                settings.forwardingMode shouldBe "LEGACY"
                settings.proxyProtocol shouldBe true
            }

            should("clear settings by setting null values") {
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

            should("return 422 for an invalid forwarding mode") {
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

            should("return 422 for a non-positive maxPlayers") {
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

            should("return 409 when updating proxy settings on a non-proxy server") {
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

            should("return 404 when getting proxy settings for a non-existent server") {
                val ex = shouldThrow<ClientException> {
                    api.getProxySettings("00000000-0000-0000-0000-000000000000")
                }
                ex.statusCode shouldBe 404
            }

            should("start a proxy with the PATCH_DEFINITIONS env var") {
                api.updateProxySettings(
                    proxyServerId,
                    UpdateProxySettingsRequest(
                        motd = "My Proxy",
                        maxPlayers = 20,
                        forwardingMode = "legacy",
                        proxyProtocol = true
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
                    haproxy-protocol = false

                    [servers]
                    try = []

                    [forced-hosts]
                    "lobby.example.com" = ["lobby"]
                """.trimIndent()
                execInContainer(proxyContainer, "sh", "-c", "cat > /server/velocity.toml <<'EOF'\n$seedVelocityToml\nEOF")
                execInContainer(proxyContainer, "mc-image-helper", "patch", "/server/craftpanel-patch.json")

                val tomlText = execInContainer(proxyContainer, "cat", "/server/velocity.toml")
                val toml = Toml.parse(tomlText)
                toml.hasErrors() shouldBe false

                val servers = toml.getTable("servers")!!
                servers.keySet() shouldContainExactly setOf("game-server-1", "try")
                servers.getString("game-server-1") shouldBe "${api.getServer(gameServerId).name}:25565"
                servers.getArray("try")!!.toList() shouldContainExactly listOf("game-server-1")

                // The stock forced-hosts entries must be cleared — they reference servers that
                // don't exist, which makes Velocity log an error on every boot.
                (toml.getTable("forced-hosts")?.keySet() ?: emptySet()).isEmpty() shouldBe true

                // PROXY-protocol listener flag is patched in (Velocity top-level haproxy-protocol).
                toml.getBoolean("haproxy-protocol") shouldBe true
            }

            should("write the master-minted forwarding secret to the proxy and its backends") {
                // MODERN forwarding: master mints one secret and writes it to both sides (ADR-0003).
                api.updateProxySettings(
                    proxyServerId,
                    UpdateProxySettingsRequest(
                        motd = "My Proxy",
                        maxPlayers = 20,
                        forwardingMode = "modern"
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

                val proxySecret = execInContainer(containerName(proxyServerId), "cat", "/server/forwarding.secret")
                proxySecret.isNotBlank() shouldBe true

                val backendPatch = execInContainer(containerName(gameServerId), "cat", "/data/craftpanel-paper-global.yml")
                backendPatch shouldContain proxySecret.trim()
            }

            should("patch the BungeeCord listener proxy_protocol flag") {
                api.updateProxySettings(
                    bungeeProxyId,
                    UpdateProxySettingsRequest(
                        motd = "My Bungee",
                        maxPlayers = 20,
                        forwardingMode = "legacy",
                        proxyProtocol = true
                    )
                )
                api.replaceProxyBackends(
                    bungeeProxyId,
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
                api.startServer(bungeeProxyId)
                helper.awaitStatus(bungeeProxyId, ServerStatus.HEALTHY)

                // Same stub trick as the Velocity case: seed a default config.yml shaped like the
                // real itzg/mc-proxy image's, then run the real mc-image-helper against the patch
                // master wrote. BungeeCord/Waterfall parse `listeners[0].proxy_protocol` (there is
                // no top-level haproxy-protocol as in Velocity).
                val proxyContainer = containerName(bungeeProxyId)
                val seedConfigYml = """
                    listeners:
                    - query_port: 25577
                      motd: '&1A BungeeCord Server'
                      tab_list: GLOBAL_PING
                      query_enabled: false
                      proxy_protocol: false
                      forced_hosts:
                        pvp.md-5.net: pvp
                      ping_passthrough: false
                      priorities:
                        - lobby
                      bind_local_address: true
                      host: 0.0.0.0:25577
                      max_players: 1
                      tab_size: 60
                      force_default_server: false

                    servers:
                      lobby:
                        motd: '&1Just another BungeeCord - Forced Host'
                        address: localhost:25565
                        restricted: false

                    player_limit: -1
                    ip_forward: false
                    online_mode: true
                """.trimIndent()
                execInContainer(proxyContainer, "sh", "-c", "cat > /server/config.yml <<'EOF'\n$seedConfigYml\nEOF")
                execInContainer(proxyContainer, "mc-image-helper", "patch", "/server/craftpanel-patch.json")

                val proxyProtocolLine = execInContainer(
                    proxyContainer,
                    "sh",
                    "-c",
                    "grep -E '^[[:space:]]*proxy_protocol:' /server/config.yml"
                )
                proxyProtocolLine.trim() shouldBe "proxy_protocol: true"
            }
        }
    }
}
