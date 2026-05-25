package io.craftpanel.agent.grpc

import com.craftpanel.agent.v1.ControlServiceGrpcKt
import com.craftpanel.agent.v1.identifyNodeRequest
import com.craftpanel.agent.v1.nodeMetadata
import com.craftpanel.agent.v1.registerNodeRequest
import io.craftpanel.agent.auth.NodeKeyStore
import io.craftpanel.agent.config.AgentConfig
import io.grpc.ManagedChannel
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

data class NodeIdentity(val nodeId: String, val nodeKey: String)

class NodeAuthenticator(private val config: AgentConfig) {
    private val log = LoggerFactory.getLogger(NodeAuthenticator::class.java)

    suspend fun authenticate(channel: ManagedChannel): NodeIdentity {
        val stub = ControlServiceGrpcKt.ControlServiceCoroutineStub(channel)
        val metadata = nodeMetadata {
            hostname = java.net.InetAddress.getLocalHost().hostName
            publicIp = resolvePublicIp()
            privateIp = resolvePrivateIp()
            agentVersion = config.agentVersion
        }

        val existingKey = NodeKeyStore.read(config.keyFilePath)

        if (existingKey == null) {
            log.info("No node key found — registering with master using bootstrap token")
            val response = stub.registerNode(registerNodeRequest {
                bootstrapToken = config.bootstrapToken
                this.metadata = metadata
            })
            NodeKeyStore.write(config.keyFilePath, response.nodeKey)
            log.info("Registered as node ${response.nodeId} — status PENDING, awaiting admin approval")
            return waitForApproval(stub, response.nodeKey, metadata)
        }

        log.info("Node key found — identifying with master")
        return waitForApproval(stub, existingKey, metadata)
    }

    private suspend fun waitForApproval(
        stub: ControlServiceGrpcKt.ControlServiceCoroutineStub,
        nodeKey: String,
        metadata: com.craftpanel.agent.v1.NodeMetadata,
    ): NodeIdentity {
        while (true) {
            val response = stub.identifyNode(identifyNodeRequest {
                this.nodeKey = nodeKey
                this.metadata = metadata
            })

            when (response.status) {
                com.craftpanel.agent.v1.IdentifyNodeResponse.IdentifyStatus.ACTIVE -> {
                    log.info("Node ${response.nodeId} is ACTIVE")
                    return NodeIdentity(nodeId = response.nodeId, nodeKey = nodeKey)
                }
                com.craftpanel.agent.v1.IdentifyNodeResponse.IdentifyStatus.PENDING -> {
                    log.info("Node ${response.nodeId} is PENDING — retrying in 30s")
                    delay(30.seconds)
                }
                else -> {
                    log.error("Node was REJECTED by master — halting")
                    throw IllegalStateException("Node rejected by master")
                }
            }
        }
    }

    private fun resolvePublicIp(): String =
        runCatching {
            java.net.URL("https://api.ipify.org").readText().trim()
        }.getOrElse { java.net.InetAddress.getLocalHost().hostAddress }

    private fun resolvePrivateIp(): String =
        runCatching { java.net.InetAddress.getLocalHost().hostAddress }.getOrElse { "unknown" }
}
