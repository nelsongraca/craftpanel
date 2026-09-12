package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.CreateServerExtraPortRequest
import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.core.annotation.Isolate
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

@Isolate
@Tags("ServerCore")
class ServerExtraPortsTest : BaseSystemTest() {

    init {
        lateinit var serverId: String

        beforeSpec {
            serverId = helper.createTestServer(nodeId)
        }

        afterSpec {
            runCatching {
                api.stopServer(serverId)
                helper.awaitStatus(serverId, ServerStatus.STOPPED)
                api.deleteServer(serverId)
            }
            helper.awaitStoppedOrGone(serverId)
        }

        context("Server Extra Ports") {
            should("allow querying primary port and creating/deleting extra ports") {
                val initialPorts = api.getServerPorts(serverId)
                initialPorts.primaryPort.hostPort shouldBe 25565
                initialPorts.extraPorts shouldHaveSize 0

                val created = api.addServerExtraPort(
                    serverId,
                    CreateServerExtraPortRequest(
                        name = "Dynmap",
                        containerPort = 8123,
                        protocol = "TCP"
                    )
                )

                created.name shouldBe "Dynmap"
                created.containerPort shouldBe 8123
                created.protocol shouldBe "TCP"
                created.hostPort shouldNotBe 0

                val afterAdd = api.getServerPorts(serverId)
                afterAdd.extraPorts shouldHaveSize 1
                afterAdd.extraPorts[0].name shouldBe "Dynmap"

                api.deleteServerExtraPort(serverId, created.id)

                val afterDelete = api.getServerPorts(serverId)
                afterDelete.extraPorts shouldHaveSize 0
            }
        }
    }
}
