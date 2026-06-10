package craftpanel.systemtest.auth

import craftpanel.systemtest.harness.AuthHelper
import craftpanel.systemtest.harness.SharedStack
import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.LoginRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.openapitools.client.infrastructure.ApiClient
import org.openapitools.client.infrastructure.ClientException

class AuthSecurityTest : DescribeSpec() {

    private val adminApi: DefaultApi by lazy { DefaultApi(basePath = SharedStack.masterApiUrl) }

    init {
        beforeSpec {
            AuthHelper(adminApi).login()
        }

        describe("Token security") {

            it("refresh without refresh cookie returns 401") {
                val freshApi = DefaultApi(basePath = SharedStack.masterApiUrl)
                val ex = shouldThrow<ClientException> { freshApi.authRefresh() }
                ex.statusCode shouldBe 401
            }

            it("login with new credentials works after logout") {
                val adminToken = ApiClient.accessToken
                val tempEmail = "authsec-${System.currentTimeMillis()}@test.com"
                val tempPw = "test-pw"
                adminApi.createUser(
                    craftpanel.systemtest.client.model.CreateUserRequest(
                        username = "authsec-${System.currentTimeMillis()}", email = tempEmail, password = tempPw
                    )
                )
                try {
                    val loginResponse = adminApi.authLogin(LoginRequest(tempEmail, tempPw))
                    loginResponse.accessToken.shouldNotBeEmpty()

                    val tempApi = DefaultApi(basePath = SharedStack.masterApiUrl)
                    AuthHelper(tempApi).login(email = tempEmail, password = tempPw)
                    tempApi.authLogout()

                    val reLogin = tempApi.authLogin(LoginRequest(tempEmail, tempPw))
                    reLogin.accessToken.shouldNotBeEmpty()
                } finally {
                    ApiClient.accessToken = adminToken
                    runCatching {
                        val user = adminApi.listUsers().users.first { it.email == tempEmail }
                        adminApi.deleteUser(user.id)
                    }
                }
            }

            it("logout-all succeeds when authenticated") {
                adminApi.authLogoutAll()
                adminApi.authMe()
            }

            it("expired access token returns 401") {
                val savedToken = ApiClient.accessToken
                try {
                    val response = adminApi.authLogin(
                        LoginRequest(craftpanel.systemtest.harness.ADMIN_EMAIL, craftpanel.systemtest.harness.ADMIN_PASSWORD)
                    )
                    ApiClient.accessToken = response.accessToken
                    adminApi.authMe()

                    ApiClient.accessToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDAiLCJuYW1lIjoiSW52YWxpZCIsImlhdCI6MTUxNjIzOTAyMn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
                    shouldThrow<ClientException> { adminApi.authMe() }.statusCode shouldBe 401
                } finally {
                    ApiClient.accessToken = savedToken
                }
            }

            it("refresh after user deactivation returns 401") {
                val adminToken = ApiClient.accessToken
                val tempEmail = "authsec-deact-${System.currentTimeMillis()}@test.com"
                val tempPw = "test-pw"
                val userObj = adminApi.createUser(
                    craftpanel.systemtest.client.model.CreateUserRequest(
                        username = "authsec-deact-${System.currentTimeMillis()}",
                        email = tempEmail,
                        password = tempPw
                    )
                )
                val userId = userObj.id
                try {
                    val userApi = DefaultApi(basePath = SharedStack.masterApiUrl)
                    val userLogin = userApi.authLogin(LoginRequest(tempEmail, tempPw))
                    ApiClient.accessToken = adminToken
                    adminApi.updateUser(userId, craftpanel.systemtest.client.model.PatchUserRequest(isActive = false))
                    ApiClient.accessToken = userLogin.accessToken

                    shouldThrow<ClientException> { userApi.authRefresh() }.statusCode shouldBe 401
                } finally {
                    ApiClient.accessToken = adminToken
                    adminApi.updateUser(userId, craftpanel.systemtest.client.model.PatchUserRequest(isActive = true))
                    adminApi.deleteUser(userId)
                }
            }
        }
    }
}
