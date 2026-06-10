package craftpanel.systemtest.harness

import craftpanel.systemtest.client.api.DefaultApi
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension

object SystemTestConfig : AbstractProjectConfig() {

    override val extensions: List<Extension> = listOf(TimingListener)

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
