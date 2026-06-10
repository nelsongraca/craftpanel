package craftpanel.systemtest.auth

import craftpanel.systemtest.client.model.CreateAssignmentRequest
import craftpanel.systemtest.client.model.CreateGroupRequest
import craftpanel.systemtest.client.model.CreateNetworkRequest
import craftpanel.systemtest.client.model.CreateServerRequest
import craftpanel.systemtest.client.model.CreateUserRequest
import craftpanel.systemtest.client.model.PatchUserRequest
import craftpanel.systemtest.client.model.PutGroupPermissionsRequest
import craftpanel.systemtest.harness.AuthHelper
import craftpanel.systemtest.harness.BaseSystemTest
import craftpanel.systemtest.harness.ServerHelper
import craftpanel.systemtest.client.api.DefaultApi
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.openapitools.client.infrastructure.ApiClient
import org.openapitools.client.infrastructure.ClientException

class PermissionsTest : BaseSystemTest() {

    private lateinit var helper: ServerHelper
    private lateinit var serverId: String

    init {
        beforeSpec {
            helper = ServerHelper(api)
            serverId = helper.createTestServer(nodeId)
        }

        afterSpec {
            runCatching { api.deleteServer(serverId) }
        }

        describe("Multi-user permission enforcement") {

            it("user without any assignment cannot list servers") {
                val email = "perm-no-list-${System.currentTimeMillis()}@test.com"
                createViewerUser(email, "pw")
                withViewerApi(email, "pw") { vApi ->
                    val servers = vApi.listServers()
                    servers.shouldBeEmpty()
                }
                cleanupUser(email)
            }

            it("user without any assignment cannot list users") {
                val email = "perm-no-users-${System.currentTimeMillis()}@test.com"
                createViewerUser(email, "pw")
                withViewerApi(email, "pw") { vApi ->
                    val ex = shouldThrow<ClientException> { vApi.listUsers() }
                    ex.statusCode shouldBe 403
                }
                cleanupUser(email)
            }

            it("user without any assignment cannot view system settings") {
                val email = "perm-no-settings-${System.currentTimeMillis()}@test.com"
                createViewerUser(email, "pw")
                withViewerApi(email, "pw") { vApi ->
                    val ex = shouldThrow<ClientException> { vApi.getSystemSettings() }
                    ex.statusCode shouldBe 403
                }
                cleanupUser(email)
            }

            it("user without any assignment cannot list nodes") {
                val email = "perm-no-nodes-${System.currentTimeMillis()}@test.com"
                createViewerUser(email, "pw")
                withViewerApi(email, "pw") { vApi ->
                    val ex = shouldThrow<ClientException> { vApi.listNodes() }
                    ex.statusCode shouldBe 403
                }
                cleanupUser(email)
            }

            it("user with GLOBAL viewer can list servers and see details") {
                val email = "perm-global-view-${System.currentTimeMillis()}@test.com"
                val (_, groupId) = createViewerUserWithGlobalAssignment(email, "pw")
                withViewerApi(email, "pw") { vApi ->
                    val servers = vApi.listServers()
                    servers.map { it.id } shouldContain serverId

                    val server = vApi.getServer(serverId)
                    server.id shouldBe serverId

                    val networks = vApi.listNetworks()
                    networks shouldNotBe null
                }
                api.deleteGroup(groupId)
                cleanupUser(email)
            }

            it("user with GLOBAL viewer cannot start/stop/delete servers") {
                val email = "perm-global-cmd-${System.currentTimeMillis()}@test.com"
                val (_, groupId) = createViewerUserWithGlobalAssignment(email, "pw")
                withViewerApi(email, "pw") { vApi ->
                    shouldThrow<ClientException> { vApi.startServer(serverId) }.statusCode shouldBe 403
                    shouldThrow<ClientException> { vApi.stopServer(serverId) }.statusCode shouldBe 403
                    shouldThrow<ClientException> { vApi.deleteServer(serverId) }.statusCode shouldBe 403
                }
                api.deleteGroup(groupId)
                cleanupUser(email)
            }

            it("user with GLOBAL viewer cannot access admin endpoints") {
                val email = "perm-global-admin-${System.currentTimeMillis()}@test.com"
                val (_, groupId) = createViewerUserWithGlobalAssignment(email, "pw")
                withViewerApi(email, "pw") { vApi ->
                    shouldThrow<ClientException> { vApi.listUsers() }
                    shouldThrow<ClientException> { vApi.listGroups() }
                    shouldThrow<ClientException> { vApi.getSystemSettings() }
                    shouldThrow<ClientException> { vApi.listNodes() }
                }
                api.deleteGroup(groupId)
                cleanupUser(email)
            }

            it("user with SERVER-scoped assignment sees only the assigned server") {
                val otherServer = helper.createTestServer(nodeId)
                val email = "perm-scoped-${System.currentTimeMillis()}@test.com"
                val groupId = createScopedViewerUser(email, "pw", serverId)
                withViewerApi(email, "pw") { vApi ->
                    val servers = vApi.listServers()
                    servers.map { it.id } shouldContain serverId
                    servers.map { it.id } shouldNotContain otherServer
                }
                api.deleteGroup(groupId)
                cleanupUser(email)
                runCatching { api.deleteServer(otherServer) }
            }

            it("user with SERVER-scoped assignment can get the assigned server") {
                val email = "perm-scoped-get-${System.currentTimeMillis()}@test.com"
                val groupId = createScopedViewerUser(email, "pw", serverId)
                withViewerApi(email, "pw") { vApi ->
                    val server = vApi.getServer(serverId)
                    server.id shouldBe serverId
                }
                api.deleteGroup(groupId)
                cleanupUser(email)
            }

            it("inactive user cannot login") {
                val email = "perm-inactive-${System.currentTimeMillis()}@test.com"
                val (userId, groupId) = createViewerUserWithGlobalAssignment(email, "pw")
                api.updateUser(userId, PatchUserRequest(isActive = false))
                val ex = shouldThrow<ClientException> {
                    AuthHelper(DefaultApi(basePath = masterApiUrl))
                        .login(email = email, password = "pw")
                }
                ex.statusCode shouldBe 401
                api.updateUser(userId, PatchUserRequest(isActive = true))
                api.deleteGroup(groupId)
                cleanupUser(email)
            }

            it("user with NETWORK-scoped assignment sees all servers in the network") {
                val net = api.createNetwork(
                    CreateNetworkRequest(name = "perm-net-${System.currentTimeMillis()}", type = "NORMAL")
                )
                val s1 = api.createServer(
                    CreateServerRequest(
                        name = "net-s1-${System.currentTimeMillis()}", nodeId = nodeId,
                        serverType = "PAPER", mcVersion = "1.21.4", itzgImageTag = "latest",
                        memoryMb = 256, cpuShares = 64, networkId = net.id
                    )
                )
                val s2 = api.createServer(
                    CreateServerRequest(
                        name = "net-s2-${System.currentTimeMillis()}", nodeId = nodeId,
                        serverType = "PAPER", mcVersion = "1.21.4", itzgImageTag = "latest",
                        memoryMb = 256, cpuShares = 64, networkId = net.id
                    )
                )
                try {
                    val email = "perm-net-scope-${System.currentTimeMillis()}@test.com"
                    val group = api.createGroup(
                        CreateGroupRequest(name = "net-scope-group-${System.currentTimeMillis()}")
                    )
                    api.setGroupPermissions(
                        group.id, PutGroupPermissionsRequest(permissions = listOf("server.view"))
                    )
                    val user = api.createUser(
                        CreateUserRequest(
                            username = "net-scope-${System.currentTimeMillis()}", email = email, password = "pw"
                        )
                    )
                    api.createAssignment(
                        user.id, CreateAssignmentRequest(
                            groupId = group.id, scopeType = "NETWORK", scopeId = net.id
                        )
                    )
                    withViewerApi(email, "pw") { vApi ->
                        val servers = vApi.listServers()
                        servers.map { it.id } shouldContain s1.id
                        servers.map { it.id } shouldContain s2.id
                    }
                    api.deleteGroup(group.id)
                    cleanupUser(email)
                } finally {
                    runCatching { api.deleteServer(s1.id) }
                    runCatching { api.deleteServer(s2.id) }
                    runCatching { api.deleteNetwork(net.id) }
                }
            }

            it("user with start but not stop permission can start but not stop") {
                val email = "perm-start-only-${System.currentTimeMillis()}@test.com"
                val group = api.createGroup(
                    CreateGroupRequest(name = "start-only-group-${System.currentTimeMillis()}")
                )
                api.setGroupPermissions(
                    group.id, PutGroupPermissionsRequest(permissions = listOf("server.start", "server.view"))
                )
                val user = api.createUser(
                    CreateUserRequest(
                        username = "start-only-${System.currentTimeMillis()}", email = email, password = "pw"
                    )
                )
                api.createAssignment(
                    user.id, CreateAssignmentRequest(
                        groupId = group.id, scopeType = "GLOBAL"
                    )
                )
                try {
                    withViewerApi(email, "pw") { vApi ->
                        vApi.startServer(serverId)
                        val ex = shouldThrow<ClientException> { vApi.stopServer(serverId) }
                        ex.statusCode shouldBe 403
                    }
                } finally {
                    api.deleteGroup(group.id)
                    cleanupUser(email)
                }
            }

            it("deleted user cannot login") {
                val email = "perm-deleted-${System.currentTimeMillis()}@test.com"
                val (userId, groupId) = createViewerUserWithGlobalAssignment(email, "pw")
                api.deleteUser(userId)
                val ex = shouldThrow<ClientException> {
                    AuthHelper(DefaultApi(basePath = masterApiUrl))
                        .login(email = email, password = "pw")
                }
                ex.statusCode shouldBe 401
                api.deleteGroup(groupId)
                cleanupUser(email)
            }

            it("user without server.view sees empty server list") {
                val email = "perm-no-view-${System.currentTimeMillis()}@test.com"
                val group = api.createGroup(
                    CreateGroupRequest(name = "no-view-group-${System.currentTimeMillis()}")
                )
                api.setGroupPermissions(
                    group.id, PutGroupPermissionsRequest(permissions = listOf("system.settings"))
                )
                val user = api.createUser(
                    CreateUserRequest(
                        username = "no-view-${System.currentTimeMillis()}", email = email, password = "pw"
                    )
                )
                api.createAssignment(
                    user.id, CreateAssignmentRequest(groupId = group.id, scopeType = "GLOBAL")
                )
                try {
                    withViewerApi(email, "pw") { vApi ->
                        val servers = vApi.listServers()
                        servers.shouldBeEmpty()
                    }
                } finally {
                    api.deleteGroup(group.id)
                    cleanupUser(email)
                }
            }

            it("user without system.users gets 403 from listUsers") {
                val email = "perm-no-users-${System.currentTimeMillis()}@test.com"
                val group = api.createGroup(
                    CreateGroupRequest(name = "no-users-group-${System.currentTimeMillis()}")
                )
                api.setGroupPermissions(
                    group.id, PutGroupPermissionsRequest(permissions = listOf("server.view"))
                )
                val user = api.createUser(
                    CreateUserRequest(
                        username = "no-users-${System.currentTimeMillis()}", email = email, password = "pw"
                    )
                )
                api.createAssignment(
                    user.id, CreateAssignmentRequest(groupId = group.id, scopeType = "GLOBAL")
                )
                try {
                    withViewerApi(email, "pw") { vApi ->
                        val ex = shouldThrow<ClientException> { vApi.listUsers() }
                        ex.statusCode shouldBe 403
                    }
                } finally {
                    api.deleteGroup(group.id)
                    cleanupUser(email)
                }
            }

            it("user without system.settings gets 403 from getSystemSettings") {
                val email = "perm-no-settings-${System.currentTimeMillis()}@test.com"
                val group = api.createGroup(
                    CreateGroupRequest(name = "no-settings-group-${System.currentTimeMillis()}")
                )
                api.setGroupPermissions(
                    group.id, PutGroupPermissionsRequest(permissions = listOf("server.view"))
                )
                val user = api.createUser(
                    CreateUserRequest(
                        username = "no-settings-${System.currentTimeMillis()}", email = email, password = "pw"
                    )
                )
                api.createAssignment(
                    user.id, CreateAssignmentRequest(groupId = group.id, scopeType = "GLOBAL")
                )
                try {
                    withViewerApi(email, "pw") { vApi ->
                        val ex = shouldThrow<ClientException> { vApi.getSystemSettings() }
                        ex.statusCode shouldBe 403
                    }
                } finally {
                    api.deleteGroup(group.id)
                    cleanupUser(email)
                }
            }
        }
    }

    private suspend fun createViewerUser(email: String, password: String) {
        val group = api.createGroup(
            CreateGroupRequest(name = "viewer-group-${System.currentTimeMillis()}")
        )
        api.setGroupPermissions(
            group.id,
            PutGroupPermissionsRequest(permissions = listOf("server.view"))
        )
        api.createUser(
            CreateUserRequest(
                username = "viewer-${System.currentTimeMillis()}",
                email = email,
                password = password
            )
        )
    }

    private suspend fun createViewerUserWithGlobalAssignment(email: String, password: String): Pair<String, String> {
        val group = api.createGroup(
            CreateGroupRequest(name = "viewer-group-${System.currentTimeMillis()}")
        )
        api.setGroupPermissions(
            group.id,
            PutGroupPermissionsRequest(permissions = listOf("server.view"))
        )
        val user = api.createUser(
            CreateUserRequest(
                username = "viewer-${System.currentTimeMillis()}",
                email = email,
                password = password
            )
        )
        api.createAssignment(
            user.id,
            CreateAssignmentRequest(
                groupId = group.id,
                scopeType = "GLOBAL"
            )
        )
        return user.id to group.id
    }

    private suspend fun createScopedViewerUser(email: String, password: String, scopedServerId: String): String {
        val group = api.createGroup(
            CreateGroupRequest(name = "scoped-group-${System.currentTimeMillis()}")
        )
        api.setGroupPermissions(
            group.id,
            PutGroupPermissionsRequest(permissions = listOf("server.view"))
        )
        val user = api.createUser(
            CreateUserRequest(
                username = "scoped-${System.currentTimeMillis()}",
                email = email,
                password = password
            )
        )
        api.createAssignment(
            user.id,
            CreateAssignmentRequest(
                groupId = group.id,
                scopeType = "SERVER",
                scopeId = scopedServerId
            )
        )
        return group.id
    }

    private suspend fun cleanupUser(email: String) {
        try {
            val user = api.listUsers().users.first { it.email == email }
            runCatching { api.deleteUser(user.id) }
        } catch (_: Exception) { }
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
