package craftpanel.systemtest.auth

import craftpanel.systemtest.harness.ADMIN_EMAIL
import craftpanel.systemtest.harness.ADMIN_PASSWORD
import craftpanel.systemtest.harness.AuthHelper
import craftpanel.systemtest.harness.SharedStack
import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.LoginRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.openapitools.client.infrastructure.ApiClient
import org.openapitools.client.infrastructure.ClientException

class AuthTest : DescribeSpec() {

    private val api: DefaultApi by lazy { DefaultApi(basePath = SharedStack.masterApiUrl) }

    init {
        beforeSpec {
            AuthHelper(api).login()
        }

        describe("Authentication") {

            it("returns 401 for invalid credentials") {
                val ex = shouldThrow<ClientException> {
                    api.authLogin(LoginRequest("nonexistent@test.com", "wrong-password"))
                }
                ex.statusCode shouldBe 401
            }

            it("returns 401 for wrong password") {
                val ex = shouldThrow<ClientException> {
                    api.authLogin(LoginRequest(ADMIN_EMAIL, "wrong-password"))
                }
                ex.statusCode shouldBe 401
            }

            it("returns 401 for wrong email") {
                val ex = shouldThrow<ClientException> {
                    api.authLogin(LoginRequest("wrong@craftpanel.test", ADMIN_PASSWORD))
                }
                ex.statusCode shouldBe 401
            }

            it("returns access token on successful login") {
                val response = api.authLogin(LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD))
                response.accessToken.shouldNotBeEmpty()
                response.expiresIn shouldBe 900 // 15 minutes in seconds
            }

            it("returns 401 when accessing protected endpoint without token") {
                ApiClient.accessToken = null
                val ex = shouldThrow<ClientException> {
                    api.listServers()
                }
                ex.statusCode shouldBe 401
            }

            it("returns current user info via GET /api/auth/me") {
                AuthHelper(api).login()
                val me = api.authMe()
                me.email shouldBe ADMIN_EMAIL
                me.id.shouldNotBeEmpty()
                me.username.shouldNotBeEmpty()
                me.groups shouldBe listOf("Super Admin")
                me.permissions shouldBe listOf("*")
            }

            it("issues WebSocket ticket via POST /api/auth/ws-ticket") {
                AuthHelper(api).login()
                val ticket = api.authWsTicket()
                ticket.ticket.shouldNotBeEmpty()
                ticket.expiresIn shouldBe 30
            }

            it("logout returns 204") {
                AuthHelper(api).login()
                api.authLogout()
            }

            it("logout-all returns 204") {
                AuthHelper(api).login()
                api.authLogoutAll()
            }
        }
    }
}
