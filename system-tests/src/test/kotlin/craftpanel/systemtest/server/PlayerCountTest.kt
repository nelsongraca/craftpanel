package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.EnvVarItem
import craftpanel.systemtest.client.model.PutEnvVarsRequest
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class PlayerCountTest : BaseSystemTest() {

    init {
        describe("Player count via MC Query protocol") {

            it("agent reports correct player count and names from fake server") {
                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(nodeId)
                try {
                    api.replaceEnvVars(
                        serverId, PutEnvVarsRequest(
                            envVars = listOf(
                                EnvVarItem(key = "ONLINE_PLAYERS", value = "Steve,Alex")
                            )
                        )
                    )
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")

                    val server = helper.awaitPlayerCount(serverId, expected = 2)
                    server.lastPlayerCount shouldBe 2
                    server.lastPlayerNames shouldContainExactlyInAnyOrder listOf("Steve", "Alex")
                }
                finally {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }
            }

            it("agent reports zero players when no players are online") {
                val helper = ServerHelper(api)
                val serverId = helper.createTestServer(nodeId)
                try {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, "HEALTHY")

                    // awaitPlayerCount(0) waits until the agent has polled at least once
                    val server = helper.awaitPlayerCount(serverId, expected = 0)
                    server.lastPlayerCount shouldBe 0
                    server.lastPlayerNames shouldBe null
                }
                finally {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    runCatching { api.deleteServer(serverId) }
                }
            }
        }
    }
}
