package craftpanel.systemtest.server

import craftpanel.systemtest.client.model.ServerStatus
import craftpanel.systemtest.client.model.ServerType
import craftpanel.systemtest.client.model.UpdateServerRequest
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ClientException

@Tags("ServerCore", "ServerCoreLifecycle")
class ServerTypeChangeTest : BaseSystemTest() {

    init {

        context("Change server type") {

            should("change a stopped server's type and apply the new TYPE on start") {
                val serverId = helper.createTestServer(nodeId, serverType = ServerType.VANILLA)
                try {
                    api.getServer(serverId).serverType shouldBe ServerType.VANILLA

                    api.updateServer(serverId, UpdateServerRequest(serverType = ServerType.FABRIC))
                    api.getServer(serverId).serverType shouldBe ServerType.FABRIC

                    api.startServer(serverId)
                    helper.awaitStatus(serverId, ServerStatus.HEALTHY)
                    docker.inspectContainerCmd(containerName(serverId)).exec()
                        .config?.env?.toList().orEmpty() shouldContain "TYPE=FABRIC"

                    api.stopServer(serverId)
                    helper.awaitStoppedOrGone(serverId)
                } finally {
                    runCatching {
                        api.stopServer(serverId)
                        helper.awaitStoppedOrGone(serverId)
                        api.deleteServer(serverId)
                    }
                }
            }

            should("reject a type change while the server is running") {
                val serverId = helper.createTestServer(nodeId, serverType = ServerType.PAPER)
                try {
                    api.startServer(serverId)
                    helper.awaitStatus(serverId, ServerStatus.HEALTHY)

                    shouldThrow<ClientException> {
                        api.updateServer(serverId, UpdateServerRequest(serverType = ServerType.FABRIC))
                    }.statusCode shouldBe 409
                } finally {
                    runCatching {
                        api.stopServer(serverId)
                        helper.awaitStoppedOrGone(serverId)
                        api.deleteServer(serverId)
                    }
                }
            }
        }
    }
}
