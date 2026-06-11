package io.craftpanel.agent.grpc

import io.craftpanel.proto.ControlServiceGrpcKt
import io.craftpanel.proto.IdentifyNodeResponse
import io.craftpanel.proto.identifyNodeRequest
import io.craftpanel.proto.nodeMetadata
import io.craftpanel.proto.registerNodeRequest
import io.craftpanel.agent.auth.NodeKeyStore
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.docker.MetricsCollector
import io.grpc.ManagedChannel
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI

data class NodeIdentity(val nodeId: String, val nodeKey: String)

class NodeAuthenticator(
    private val config: AgentConfig,
    private val metricsCollector: MetricsCollector,
) {

    private val log = LoggerFactory.getLogger(NodeAuthenticator::class.java)

    suspend fun authenticate(channel: ManagedChannel): NodeIdentity {
        val stub = ControlServiceGrpcKt.ControlServiceCoroutineStub(channel)
        val (rawTotalRamMb, totalCpuShares) = metricsCollector.collectCapacity()
        val totalRamMb = maxOf(0, rawTotalRamMb - config.systemReservedRamMb)
        val metadata = nodeMetadata {
            hostname = config.hostnameOverride.ifBlank { InetAddress.getLocalHost().hostName }
            publicIp = resolvePublicIp()
            privateIp = resolvePrivateIp()
            agentVersion = config.agentVersion
            this.totalRamMb = totalRamMb
            this.totalCpuShares = totalCpuShares
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
            return NodeIdentity(nodeId = response.nodeId, nodeKey = response.nodeKey)
        }

        log.info("Node key found — identifying with master")
        val response = stub.identifyNode(identifyNodeRequest {
            nodeKey = existingKey
            this.metadata = metadata
        })

        return when (response.status) {
            IdentifyNodeResponse.IdentifyStatus.ACTIVE  -> {
                log.info("Node ${response.nodeId} is ACTIVE")
                NodeIdentity(nodeId = response.nodeId, nodeKey = existingKey)
            }

            IdentifyNodeResponse.IdentifyStatus.PENDING -> {
                log.info("Node ${response.nodeId} is PENDING — awaiting admin approval")
                NodeIdentity(nodeId = response.nodeId, nodeKey = existingKey)
            }

            else                                        -> throw NodeRejectedException("Node ${response.nodeId} was REJECTED by master")
        }
    }

    private fun resolvePublicIp(): String {
        if (config.publicIpUrl.isBlank()) return resolvePrivateIp()
        return runCatching {
            val conn = URI(config.publicIpUrl).toURL()
                .openConnection()
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.getInputStream()
                .bufferedReader()
                .readText()
                .trim()
        }.getOrElse { resolvePrivateIp() }
    }

    private fun resolvePrivateIp(): String =
        runCatching { InetAddress.getLocalHost().hostAddress }.getOrElse { "unknown" }
}
