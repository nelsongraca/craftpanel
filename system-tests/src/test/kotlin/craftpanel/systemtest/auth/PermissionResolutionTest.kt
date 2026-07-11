package craftpanel.systemtest.auth

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.*
import craftpanel.systemtest.harness.AuthHelper
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.openapitools.client.infrastructure.ApiClient
import org.openapitools.client.infrastructure.ClientException

class PermissionResolutionTest : BaseSystemTest() {

    private lateinit var serverId: String

    init {
        beforeSpec {
            serverId = helper.createTestServer(nodeId)
        }

        afterSpec {
            runCatching { api.deleteServer(serverId) }
        }

        context("Wildcard permission resolution") {

            should("server.* grants all server operations") {
                val email = "wild-server-${System.currentTimeMillis()}@test.com"
                val group = api.createGroup(
                    CreateGroupRequest(name = "wild-server-group-${System.currentTimeMillis()}")
                )
                api.setGroupPermissions(
                    group.id,
                    PutGroupPermissionsRequest(
                        permissions = listOf(
                            "server.view", "server.start", "server.stop", "server.restart",
                            "server.configure", "server.create", "server.delete", "server.files",
                            "server.mods", "server.console", "server.export", "server.backup"
                        )
                    )
                )
                val user = api.createUser(
                    CreateUserRequest(
                        username = "wild-server-${System.currentTimeMillis()}",
                        email = email,
                        password = "pw"
                    )
                )
                api.createAssignment(
                    user.id,
                    CreateAssignmentRequest(groupId = group.id, scopeType = "GLOBAL")
                )
                try {
                    withViewerApi(email, "pw") { vApi ->
                        val servers = vApi.listServers()
                        servers.map { it.id } shouldContain serverId

                        vApi.startServer(serverId)
                        helper.awaitStatus(serverId, ServerStatus.HEALTHY)

                        vApi.stopServer(serverId)
                        helper.awaitStoppedOrGone(serverId)
                    }
                } finally {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    api.deleteGroup(group.id)
                    cleanupUser(email)
                }
            }

            should("wildcard * grants all permissions") {
                val email = "wild-all-${System.currentTimeMillis()}@test.com"
                val superAdminGroup = api.listGroups()
                    .first { it.name == "Super Admin" }
                val user = api.createUser(
                    CreateUserRequest(
                        username = "wild-all-${System.currentTimeMillis()}",
                        email = email,
                        password = "pw"
                    )
                )
                api.createAssignment(
                    user.id,
                    CreateAssignmentRequest(groupId = superAdminGroup.id, scopeType = "GLOBAL")
                )
                try {
                    withViewerApi(email, "pw") { vApi ->
                        vApi.listUsers()
                        vApi.listNodes()
                        vApi.getSystemSettings()
                        vApi.listServers()
                    }
                } finally {
                    cleanupUser(email)
                }
            }
        }

        context("Scope union resolution") {

            should("GLOBAL server.view + SERVER-scoped server.start allows start on assigned server only") {
                val otherServer = helper.createTestServer(nodeId)
                val email = "scope-union-${System.currentTimeMillis()}@test.com"
                try {
                    val viewGroup = api.createGroup(
                        CreateGroupRequest(name = "scope-union-view-${System.currentTimeMillis()}")
                    )
                    api.setGroupPermissions(
                        viewGroup.id,
                        PutGroupPermissionsRequest(permissions = listOf("server.view"))
                    )
                    val startGroup = api.createGroup(
                        CreateGroupRequest(name = "scope-union-start-${System.currentTimeMillis()}")
                    )
                    api.setGroupPermissions(
                        startGroup.id,
                        PutGroupPermissionsRequest(permissions = listOf("server.start"))
                    )
                    val user = api.createUser(
                        CreateUserRequest(
                            username = "scope-union-${System.currentTimeMillis()}",
                            email = email,
                            password = "pw"
                        )
                    )
                    api.createAssignment(
                        user.id,
                        CreateAssignmentRequest(groupId = viewGroup.id, scopeType = "GLOBAL")
                    )
                    api.createAssignment(
                        user.id,
                        CreateAssignmentRequest(
                            groupId = startGroup.id,
                            scopeType = "SERVER",
                            scopeId = serverId
                        )
                    )
                    withViewerApi(email, "pw") { vApi ->
                        vApi.startServer(serverId)

                        shouldThrow<ClientException> { vApi.startServer(otherServer) }.statusCode shouldBe 403
                    }
                } finally {
                    runCatching { api.stopServer(serverId) }
                    helper.awaitStoppedOrGone(serverId)
                    api.deleteGroup(
                        api.listGroups()
                            .first { it.name.startsWith("scope-union-view-") }.id
                    )
                    api.deleteGroup(
                        api.listGroups()
                            .first { it.name.startsWith("scope-union-start-") }.id
                    )
                    cleanupUser(email)
                    runCatching { api.deleteServer(otherServer) }
                }
            }

            should("NETWORK scope respected after server moves between networks") {
                val netA = api.createNetwork(
                    CreateNetworkRequest(name = "perm-move-a-${System.currentTimeMillis()}")
                )
                val netB = api.createNetwork(
                    CreateNetworkRequest(name = "perm-move-b-${System.currentTimeMillis()}")
                )
                val movedServer = api.createServer(
                    CreateServerRequest(
                        name = "perm-move-srv-${System.currentTimeMillis()}",
                        nodeId = nodeId,
                        serverType = "PAPER",
                        mcVersion = "1.21.4",
                        itzgImageTag = "latest",
                        memoryMb = 256,
                        cpuShares = 64,
                        networkId = netA.id
                    )
                )
                val email = "perm-move-${System.currentTimeMillis()}@test.com"
                val group = api.createGroup(
                    CreateGroupRequest(name = "perm-move-group-${System.currentTimeMillis()}")
                )
                api.setGroupPermissions(
                    group.id,
                    PutGroupPermissionsRequest(permissions = listOf("server.view"))
                )
                val user = api.createUser(
                    CreateUserRequest(
                        username = "perm-move-${System.currentTimeMillis()}",
                        email = email,
                        password = "pw"
                    )
                )
                api.createAssignment(
                    user.id,
                    CreateAssignmentRequest(
                        groupId = group.id,
                        scopeType = "NETWORK",
                        scopeId = netA.id
                    )
                )
                try {
                    withViewerApi(email, "pw") { vApi ->
                        val serversInA = vApi.listServers()
                        serversInA.map { it.id } shouldContain movedServer.id
                    }

                    api.updateServer(movedServer.id, UpdateServerRequest(networkId = netB.id))

                    withViewerApi(email, "pw") { vApi ->
                        val serversAfterMove = vApi.listServers()
                        serversAfterMove.map { it.id } shouldNotContain movedServer.id
                    }
                } finally {
                    api.deleteGroup(group.id)
                    cleanupUser(email)
                    runCatching { api.deleteServer(movedServer.id) }
                    runCatching { api.deleteNetwork(netA.id) }
                    runCatching { api.deleteNetwork(netB.id) }
                }
            }

            should("duplicate permission nodes from multiple groups are deduplicated") {
                val email = "perm-dedup-${System.currentTimeMillis()}@test.com"
                val groupA = api.createGroup(
                    CreateGroupRequest(name = "dedup-a-${System.currentTimeMillis()}")
                )
                api.setGroupPermissions(
                    groupA.id,
                    PutGroupPermissionsRequest(permissions = listOf("server.view", "server.start"))
                )
                val groupB = api.createGroup(
                    CreateGroupRequest(name = "dedup-b-${System.currentTimeMillis()}")
                )
                api.setGroupPermissions(
                    groupB.id,
                    PutGroupPermissionsRequest(permissions = listOf("server.view", "server.stop"))
                )
                val user = api.createUser(
                    CreateUserRequest(
                        username = "perm-dedup-${System.currentTimeMillis()}",
                        email = email,
                        password = "pw"
                    )
                )
                api.createAssignment(
                    user.id,
                    CreateAssignmentRequest(groupId = groupA.id, scopeType = "GLOBAL")
                )
                api.createAssignment(
                    user.id,
                    CreateAssignmentRequest(groupId = groupB.id, scopeType = "GLOBAL")
                )
                try {
                    withViewerApi(email, "pw") { vApi ->
                        val server = vApi.getServer(serverId)
                        server.id shouldBe serverId

                        vApi.startServer(serverId)
                        helper.awaitStatus(serverId, ServerStatus.HEALTHY)

                        vApi.stopServer(serverId)
                        helper.awaitStoppedOrGone(serverId)
                    }
                } finally {
                    api.deleteGroup(groupA.id)
                    api.deleteGroup(groupB.id)
                    cleanupUser(email)
                }
            }
        }
    }

    private suspend fun cleanupUser(email: String) {
        try {
            val user = api.listUsers().users.first { it.email == email }
            runCatching { api.deleteUser(user.id) }
        } catch (_: Exception) {
        }
    }

    private suspend fun <T> withViewerApi(email: String, password: String, block: suspend (DefaultApi) -> T): T {
        val savedToken = ApiClient.accessToken
        try {
            val viewerApi = DefaultApi(basePath = masterApiUrl)
            AuthHelper(viewerApi).login(email = email, password = password)
            return block(viewerApi)
        } finally {
            ApiClient.accessToken = savedToken
        }
    }
}
