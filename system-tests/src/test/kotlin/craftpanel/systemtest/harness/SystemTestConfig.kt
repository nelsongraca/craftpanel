package craftpanel.systemtest.harness

import craftpanel.systemtest.client.api.DefaultApi
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import java.io.File

object SystemTestConfig : AbstractProjectConfig() {

    override val extensions: List<Extension> = listOf(TimingListener)

    override suspend fun beforeProject() {
        val agentJar = System.getProperty("kover.agent.jar")
            ?.let(::File)
        val outputDir = System.getProperty("kover.output.dir")
            ?.let(::File)
        val coverageEnabled = agentJar != null && outputDir != null

        SharedStack.start(
            coverageEnabled = coverageEnabled,
            agentJar = agentJar,
            coverageDir = outputDir,
            nodeCount = 2,
        )

        val api = DefaultApi(basePath = SharedStack.masterApiUrl)
        AuthHelper(api).login()
        val ids = MultiNodeHelper(api).trustAllPendingNodes(2)
        SharedStack.storeNodeIds(ids)
    }

    override suspend fun afterProject() {
        SharedStack.stop()
    }
}