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
class AuthSecurityTest : BaseSystemTest() {

    init {
        context("Token security") {

            should("return 401 on refresh without a refresh cookie") {
                val freshApi = DefaultApi(basePath = SharedStack.masterApiUrl)
                val ex = shouldThrow<ClientException> { freshApi.authRefresh() }
                ex.statusCode shouldBe 401
            }

            should("allow login with new credentials after logout") {
                val tempEmail = "authsec-${System.currentTimeMillis()}@test.com"
                val tempPw = "test-pw"
                api.createUser(CreateUserRequest(username = "authsec-${System.currentTimeMillis()}", email = tempEmail, password = tempPw))
                try {
                    val loginResponse = api.authLogin(LoginRequest(tempEmail, tempPw))
                    loginResponse.accessToken.shouldNotBeEmpty()

                    val tempApi = DefaultApi(basePath = SharedStack.masterApiUrl)
                    AuthHelper(tempApi).login(email = tempEmail, password = tempPw)
                    tempApi.authLogout()

                    val reLogin = tempApi.authLogin(LoginRequest(tempEmail, tempPw))
                    reLogin.accessToken.shouldNotBeEmpty()
                } finally {
                    runCatching {
                        val user = api.listUsers().users.first { it.email == tempEmail }
                        api.deleteUser(user.id)
                    }
                }
            }

            should("succeed on logout-all when authenticated") {
                api.authLogoutAll()
                api.authMe()
            }

            should("return 401 for an expired access token") {
                val savedProvider = api.accessTokenProvider
                try {
                    val response = api.authLogin(LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD))
                    api.accessTokenProvider = { response.accessToken }
                    api.authMe()

                    val badJwt =
                        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDAiLCJuYW1lIjoiSW52YWxpZCIsImlhdCI6MTUxNjIzOTAyMn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
                    api.accessTokenProvider = { badJwt }
                    shouldThrow<ClientException> { api.authMe() }.statusCode shouldBe 401
                } finally {
                    api.accessTokenProvider = savedProvider
                }
            }

            should("return 401 on refresh after user deactivation") {
                val tempEmail = "authsec-deact-${System.currentTimeMillis()}@test.com"
                val tempPw = "test-pw"
                val userObj = api.createUser(
                    CreateUserRequest(
                        username = "authsec-deact-${System.currentTimeMillis()}",
                        email = tempEmail,
                        password = tempPw
                    )
                )
                val userId = userObj.id
                try {
                    val userApi = DefaultApi(basePath = SharedStack.masterApiUrl)
                    userApi.authLogin(LoginRequest(tempEmail, tempPw))
                    api.updateUser(userId, PatchUserRequest(isActive = false))

                    shouldThrow<ClientException> { userApi.authRefresh() }.statusCode shouldBe 401
                } finally {
                    api.updateUser(userId, PatchUserRequest(isActive = true))
                    api.deleteUser(userId)
                }
            }
        }
    }
}
