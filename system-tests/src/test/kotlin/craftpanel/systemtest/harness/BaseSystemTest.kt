package craftpanel.systemtest.harness

import com.github.dockerjava.api.DockerClient
import craftpanel.systemtest.client.api.DefaultApi
import io.kotest.core.spec.style.DescribeSpec

abstract class BaseSystemTest : DescribeSpec() {

    val api: DefaultApi by lazy { DefaultApi(basePath = CraftPanelStack.masterApiUrl) }
    val docker: DockerClient by lazy { CraftPanelStack.dockerClient }

    lateinit var nodeId: String

    init {
        beforeSpec {
            CraftPanelStack.start()
            AuthHelper(api).login()
            nodeId = NodeHelper(api).trustFirstPendingNode()
        }
        beforeTest {
            NodeHelper(api).pollUntilActive(nodeId)
        }
        afterSpec {
            CraftPanelStack.stop()
        }
    }
}
