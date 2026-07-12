package craftpanel.systemtest.server

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.*
import craftpanel.systemtest.harness.AuthHelper
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.*

@Tags("ServerOps")
class SearchModsTest : BaseSystemTest() {

    init {

        lateinit var serverId: String

        beforeSpec {
            serverId = helper.createTestServer(nodeId)
            api.startServer(serverId)
            helper.awaitStatus(serverId, ServerStatus.HEALTHY)
        }

        afterSpec {
            runCatching { api.stopServer(serverId) }
            helper.awaitStoppedOrGone(serverId)
            runCatching { api.deleteServer(serverId) }
        }

        context("searchMods") {

            should("returns 404 for non-existent server") {
                val ex = shouldThrow<ClientException> {
                    api.searchMods("00000000-0000-0000-0000-000000000000", query = "fabric-api")
                }
                ex.statusCode shouldBe 404
            }

            should("returns 403 for user without SERVER_MODS permission") {
                val email = "search-no-mods-${System.currentTimeMillis()}@test.com"
                val password = "pw-mods-test"
                val savedToken = ApiClient.accessToken
                try {
                    val group = api.createGroup(
                        CreateGroupRequest(name = "view-only-${System.currentTimeMillis()}")
                    )
                    api.setGroupPermissions(
                        group.id,
                        PutGroupPermissionsRequest(permissions = listOf("server.view"))
                    )
                    val user = api.createUser(
                        CreateUserRequest(
                            username = "view-only-${System.currentTimeMillis()}",
                            email = email,
                            password = password
                        )
                    )
                    api.createAssignment(
                        user.id,
                        CreateAssignmentRequest(groupId = group.id, scopeType = "GLOBAL")
                    )

                    val restrictedApi = DefaultApi(basePath = masterApiUrl)
                    AuthHelper(restrictedApi).login(email = email, password = password)
                    val ex = shouldThrow<ClientException> {
                        restrictedApi.searchMods(serverId, query = "sodium")
                    }
                    ex.statusCode shouldBe 403

                    runCatching { api.deleteUser(user.id) }
                    runCatching { api.deleteGroup(group.id) }
                } finally {
                    ApiClient.accessToken = savedToken
                }
            }

            should("succeeds or returns 502 when Modrinth is unreachable") {
                // searchMods proxies Modrinth; in CI without outbound internet it returns 502
                val ex = runCatching {
                    api.searchMods(serverId, query = "fabric-api", limit = 5)
                }.exceptionOrNull()
                if (ex != null) {
                    val statusCode = when (ex) {
                        is ClientException -> ex.statusCode
                        is ServerException -> ex.statusCode
                        else -> throw ex
                    }
                    statusCode shouldBe 502
                }
                // if no exception: upstream was reachable, success
            }
        }
    }
}
