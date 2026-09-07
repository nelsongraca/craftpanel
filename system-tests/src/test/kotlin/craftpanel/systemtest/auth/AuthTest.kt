package craftpanel.systemtest.auth

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.ChangePasswordRequest
import craftpanel.systemtest.client.model.LoginRequest
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

            should("returns 401 for invalid credentials") {
                val ex = shouldThrow<ClientException> {
                    api.authLogin(LoginRequest("nonexistent@test.com", "wrong-password"))
                }
                ex.statusCode shouldBe 401
            }

            should("returns 401 for wrong password") {
                val ex = shouldThrow<ClientException> {
                    api.authLogin(LoginRequest(ADMIN_EMAIL, "wrong-password"))
                }
                ex.statusCode shouldBe 401
            }

            should("returns 401 for wrong email") {
                val ex = shouldThrow<ClientException> {
                    api.authLogin(LoginRequest("wrong@craftpanel.test", ADMIN_PASSWORD))
                }
                ex.statusCode shouldBe 401
            }

            should("returns access token on successful login") {
                val response = api.authLogin(LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD))
                response.accessToken.shouldNotBeEmpty()
                response.expiresIn shouldBe 900 // 15 minutes in seconds
            }

            should("returns 401 when accessing protected endpoint without token") {
                val savedProvider = api.accessTokenProvider
                api.accessTokenProvider = { null }
                val ex = shouldThrow<ClientException> {
                    api.listServers()
                }
                ex.statusCode shouldBe 401
                api.accessTokenProvider = savedProvider
            }

            should("returns current user info via GET /api/auth/me") {
                authHelper.login()
                val me = api.authMe()
                me.email shouldBe ADMIN_EMAIL
                me.id.shouldNotBeEmpty()
                me.username.shouldNotBeEmpty()
                me.groups shouldBe listOf("Super Admin")
                me.permissions shouldBe listOf("*")
            }

            should("issues WebSocket ticket via POST /api/auth/ws-ticket") {
                authHelper.login()
                val ticket = api.authWsTicket()
                ticket.ticket.shouldNotBeEmpty()
                ticket.expiresIn shouldBe 30
            }

            should("logout returns 204") {
                authHelper.login()
                api.authLogout()
            }

            should("logout-all returns 204") {
                authHelper.login()
                api.authLogoutAll()
            }

            should("change-password with correct old password returns 204") {
                authHelper.login()
                api.authChangePassword(ChangePasswordRequest(ADMIN_PASSWORD, "new-pw-change-1"))
            }

            should("can login with new password after change-password") {
                authHelper.login()
                api.authChangePassword(ChangePasswordRequest(ADMIN_PASSWORD, "new-pw-change-2"))

                val newApi = DefaultApi(basePath = SharedStack.masterApiUrl)
                newApi.authLogin(LoginRequest(ADMIN_EMAIL, "new-pw-change-2"))
                newApi.authMe()
                newApi.authChangePassword(ChangePasswordRequest("new-pw-change-2", ADMIN_PASSWORD))
            }

            should("change-password with wrong old password returns 400") {
                authHelper.login()
                val ex = shouldThrow<ClientException> {
                    api.authChangePassword(ChangePasswordRequest("wrong-old-pw", "new-pw-change-3"))
                }
                ex.statusCode shouldBe 400
            }
        }
    }
}
