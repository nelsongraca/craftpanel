package craftpanel.systemtest.harness

import craftpanel.systemtest.client.api.DefaultApi
import io.kotest.core.config.AbstractProjectConfig

object SystemTestConfig : AbstractProjectConfig() {

    override suspend fun beforeProject() {
        SharedStack.start()
        val api = DefaultApi(basePath = SharedStack.masterApiUrl)
        AuthHelper(api).login()
        val nodeId = NodeHelper(api).trustFirstPendingNode()
        SharedStack.storeNodeId(nodeId)
    }

    override suspend fun afterProject() {
        SharedStack.stop()
    }
}
