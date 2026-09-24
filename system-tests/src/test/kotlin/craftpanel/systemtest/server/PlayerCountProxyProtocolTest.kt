package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.CreateServerRequest
import craftpanel.systemtest.client.model.EnvVarItem
import craftpanel.systemtest.client.model.PutEnvVarsRequest
import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.client.model.UpdateProxySettingsRequest
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * A proxy whose listener expects the HAProxy PROXY protocol must still report a player count: the
 * agent's `mc-monitor status --json` probe adds `--use-proxy` (driven by
 * `StartContainerCommand.proxy_protocol`), and the fake server consumes the PROXY v1 header the
 * probe sends before the Minecraft handshake.
 *
 * The proxy's internal listen port is 25577, so the fixture is pinned there via `GAME_PORT` (the
 * fake server reads `GAME_PORT`, defaulting to 25565 otherwise).
 */
@Tags("ServerCore")
class PlayerCountProxyProtocolTest : BaseSystemTest() {

    init {
        lateinit var proxyId: String

        beforeSpec {
            proxyId = api.createServer(
                CreateServerRequest(
                    name = "test-proxy-proto-${System.currentTimeMillis()}",
                    nodeId = nodeId,
                    serverType = "VELOCITY",
                    mcVersion = "latest",
                    itzgImageTag = "latest",
                    memoryMb = 256,
                    cpuLimitMillicores = 1000
                )
            ).id
            api.updateProxySettings(
                proxyId,
                UpdateProxySettingsRequest(
                    motd = null,
                    maxPlayers = null,
                    forwardingMode = null,
                    proxyProtocol = true
                )
            )
            api.replaceEnvVars(
                proxyId,
                PutEnvVarsRequest(
                    envVars = listOf(
                        EnvVarItem(key = "GAME_PORT", value = "25577"),
                        EnvVarItem(key = "ONLINE_PLAYERS", value = "Steve,Alex")
                    )
                )
            )
        }

        afterSpec {
            runCatching { api.stopServer(proxyId) }
            helper.awaitStoppedOrGone(proxyId)
            runCatching { api.deleteServer(proxyId) }
        }

        should("report the player count for a PROXY-protocol proxy") {
            api.startServer(proxyId)
            helper.awaitStatus(proxyId, ServerStatus.HEALTHY)

            val server = helper.awaitPlayerCount(proxyId, expected = 2)
            server.lastPlayerCount shouldBe 2
            server.lastPlayerNames shouldContainExactlyInAnyOrder listOf("Steve", "Alex")
        }
    }
}
