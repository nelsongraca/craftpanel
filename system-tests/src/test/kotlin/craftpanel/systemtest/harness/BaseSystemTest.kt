package craftpanel.systemtest.harness

import com.github.dockerjava.api.DockerClient
import craftpanel.systemtest.client.api.DefaultApi
import io.kotest.core.spec.style.ShouldSpec

abstract class BaseSystemTest : ShouldSpec() {

    val api: DefaultApi by lazy { DefaultApi(basePath = SharedStack.masterApiUrl) }
    val docker: DockerClient by lazy { SharedStack.dockerClient }
    val nodeId: String get() = SharedStack.nodeId
    val masterApiUrl: String get() = SharedStack.masterApiUrl
    protected val helper = ServerHelper(api)
    protected val authHelper = AuthHelper(api)
    protected val nodeHelper = NodeHelper(api)


    fun containerName(serverId: String): String = "${SharedStack.containerPrefix}-$serverId"

    init {
        beforeSpec {
            authHelper.login()
        }
        beforeTest {
            nodeHelper.pollUntilActive(nodeId)
        }
    }
}
