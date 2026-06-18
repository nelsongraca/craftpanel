package craftpanel.systemtest.auth

import craftpanel.systemtest.client.model.LoginRequest
import craftpanel.systemtest.harness.ADMIN_EMAIL
import craftpanel.systemtest.harness.ADMIN_PASSWORD
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.openapitools.client.infrastructure.ApiClient
import org.openapitools.client.infrastructure.ClientException

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
                ApiClient.accessToken = null
                val ex = shouldThrow<ClientException> {
                    api.listServers()
                }
                ex.statusCode shouldBe 401
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
        }
    }
}
