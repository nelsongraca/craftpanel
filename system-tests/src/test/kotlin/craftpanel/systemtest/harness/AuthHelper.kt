package craftpanel.systemtest.harness

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.LoginRequest

class AuthHelper(private val api: DefaultApi) {

    private var _token: String = ""
    val token: String get() = _token

    suspend fun login(email: String = ADMIN_EMAIL, password: String = ADMIN_PASSWORD) {
        val response = api.authLogin(LoginRequest(email, password))
        _token = response.accessToken
        api.accessTokenProvider = { _token }
    }
}
