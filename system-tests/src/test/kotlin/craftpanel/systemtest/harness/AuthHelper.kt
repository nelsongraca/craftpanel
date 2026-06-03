package craftpanel.systemtest.harness

import craftpanel.systemtest.client.api.DefaultApi
import craftpanel.systemtest.client.model.IocraftpanelmasterauthroutesLoginRequest
import org.openapitools.client.infrastructure.ApiClient

class AuthHelper(private val api: DefaultApi) {

    private var _token: String = ""
    val token: String get() = _token

    suspend fun login(
        email: String = ADMIN_EMAIL,
        password: String = ADMIN_PASSWORD,
    ) {
        val response = api.authLogin(IocraftpanelmasterauthroutesLoginRequest(email, password))
        _token = response.accessToken
        ApiClient.accessToken = _token
    }
}
