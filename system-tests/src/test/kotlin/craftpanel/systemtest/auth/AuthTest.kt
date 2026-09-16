package craftpanel.systemtest.auth

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.*
import craftpanel.systemtest.harness.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.openapitools.client.infrastructure.ClientException

@Tags("Auth")
class AuthTest : BaseSystemTest() {

    init {

        context("Authentication") {

            should("return 401 for invalid credentials") {
                val ex = shouldThrow<ClientException> {
                    api.authLogin(LoginRequest("nonexistent@test.com", "wrong-password"))
                }
                ex.statusCode shouldBe 401
            }

            should("return 401 for a wrong password") {
                val ex = shouldThrow<ClientException> {
                    api.authLogin(LoginRequest(ADMIN_EMAIL, "wrong-password"))
                }
                ex.statusCode shouldBe 401
            }

            should("return 401 for a wrong email") {
                val ex = shouldThrow<ClientException> {
                    api.authLogin(LoginRequest("wrong@craftpanel.test", ADMIN_PASSWORD))
                }
                ex.statusCode shouldBe 401
            }

            should("return an access token on successful login") {
                val response = api.authLogin(LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD))
                response.accessToken.shouldNotBeEmpty()
                response.expiresIn shouldBe 900 // 15 minutes in seconds
            }

            should("return 401 when accessing a protected endpoint without a token") {
                val savedProvider = api.accessTokenProvider
                api.accessTokenProvider = { null }
                val ex = shouldThrow<ClientException> {
                    api.listServers()
                }
                ex.statusCode shouldBe 401
                api.accessTokenProvider = savedProvider
            }

            should("return current user info via GET /api/auth/me") {
                authHelper.login()
                val me = api.authMe()
                me.email shouldBe ADMIN_EMAIL
                me.id.shouldNotBeEmpty()
                me.username.shouldNotBeEmpty()
                me.groups shouldBe listOf("Super Admin")
                me.permissions shouldBe listOf("*")
            }

            should("issue a WebSocket ticket via POST /api/auth/ws-ticket") {
                authHelper.login()
                val ticket = api.authWsTicket()
                ticket.ticket.shouldNotBeEmpty()
                ticket.expiresIn shouldBe 30
            }

            should("return 204 on logout") {
                authHelper.login()
                api.authLogout()
            }

            should("return 204 on logout-all") {
                authHelper.login()
                api.authLogoutAll()
            }

            should("return 204 on change-password with the correct old password") {
                val email = "pw-change-1-${System.currentTimeMillis()}@test.com"
                createPasswordTestUser(email, "old-pw-1")
                val userApi = authenticatedAs(email, "old-pw-1")
                userApi.authChangePassword(ChangePasswordRequest("old-pw-1", "new-pw-1"))
            }

            should("log in with the new password after change-password") {
                val email = "pw-change-2-${System.currentTimeMillis()}@test.com"
                createPasswordTestUser(email, "old-pw-2")
                val userApi = authenticatedAs(email, "old-pw-2")
                userApi.authChangePassword(ChangePasswordRequest("old-pw-2", "new-pw-2"))

                authenticatedAs(email, "new-pw-2").authMe()
            }

            should("return 400 on change-password with the wrong old password") {
                val email = "pw-change-3-${System.currentTimeMillis()}@test.com"
                createPasswordTestUser(email, "old-pw-3")
                val userApi = authenticatedAs(email, "old-pw-3")
                val ex = shouldThrow<ClientException> {
                    userApi.authChangePassword(ChangePasswordRequest("wrong-old-pw", "new-pw-3"))
                }
                ex.statusCode shouldBe 400
            }
        }
    }

    /**
     * Change-password tests must never mutate the shared admin password: every later spec
     * (and every beforeTest hook) logs in with ADMIN_PASSWORD, so leaving it changed turns
     * the whole run red with 401s. Each change-password test gets its own throwaway user.
     */
    private suspend fun createPasswordTestUser(email: String, password: String) {
        val group = api.createGroup(
            CreateGroupRequest(name = "pw-group-${System.currentTimeMillis()}")
        )
        api.setGroupPermissions(
            group.id,
            PutGroupPermissionsRequest(permissions = listOf("server.view"))
        )
        val user = api.createUser(
            CreateUserRequest(
                username = "pw-${System.currentTimeMillis()}",
                email = email,
                password = password
            )
        )
        api.createAssignment(
            user.id,
            CreateAssignmentRequest(groupId = group.id, scopeType = "GLOBAL")
        )
    }

    /** Logs in as [email] and returns a DefaultApi bound to that user's access token. */
    private suspend fun authenticatedAs(email: String, password: String): DefaultApi {
        val userApi = DefaultApi(basePath = SharedStack.masterApiUrl)
        val login = userApi.authLogin(LoginRequest(email, password))
        userApi.accessTokenProvider = { login.accessToken }
        return userApi
    }
}
