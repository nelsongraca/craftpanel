package io.craftpanel.agent.grpc

import io.craftpanel.agent.auth.NodeKeyStore
import io.craftpanel.agent.config.AgentConfig
import io.craftpanel.agent.config.RuntimeSettings
import io.craftpanel.agent.config.RuntimeSettingsStore
import io.craftpanel.agent.docker.MetricsCollector
import io.craftpanel.common.BuildInfo
import io.craftpanel.proto.*
import io.grpc.ManagedChannel
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI

internal class NodeRejectedException(message: String) : Exception(message)

data class NodeIdentity(val nodeId: String, val nodeKey: String)

class NodeAuthenticator(
    private val config: AgentConfig,
    private val metricsCollector: MetricsCollector,
    private val runtimeSettings: RuntimeSettingsStore
) {

    private val log = LoggerFactory.getLogger(NodeAuthenticator::class.java)

    suspend fun authenticate(channel: ManagedChannel): NodeIdentity {
        val stub = ControlServiceGrpcKt.ControlServiceCoroutineStub(channel)
        val (totalRamMb, totalCpuMillicores) = metricsCollector.collectCapacity()
        val metadata = nodeMetadata {
            hostname = config.hostnameOverride.ifBlank { InetAddress.getLocalHost().hostName }
            publicIp = resolvePublicIp()
            privateIp = resolvePrivateIp()
            agentVersion = BuildInfo.version
            this.totalRamMb = maxOf(0, totalRamMb)
            this.totalCpuMillicores = totalCpuMillicores
            this.reservedRamMb = maxOf(0, config.systemReservedRamMb)
            this.reservedCpuMillicores = maxOf(0, config.systemReservedCpuMillicores)
        }

        val existingKey = NodeKeyStore.read(config.keyFilePath)

        if (existingKey == null) {
            log.info("No node key found — registering with master using bootstrap token")
            val response = stub.registerNode(
                registerNodeRequest {
                    bootstrapToken = config.bootstrapToken
                    this.metadata = metadata
                }
            )
            NodeKeyStore.write(config.keyFilePath, response.nodeKey)
            applyRuntimeSettings(response.runtimeSettings)
            log.info("Registered as node ${response.nodeId} — status PENDING, awaiting admin approval")
            return NodeIdentity(nodeId = response.nodeId, nodeKey = response.nodeKey)
        }

        log.info("Node key found — identifying with master")
        val response = stub.identifyNode(
            identifyNodeRequest {
                nodeKey = existingKey
                this.metadata = metadata
            }
        )

        return when (response.status) {
            IdentifyNodeResponse.IdentifyStatus.ACTIVE -> {
                applyRuntimeSettings(response.runtimeSettings)
                log.info("Node ${response.nodeId} is ACTIVE")
                NodeIdentity(nodeId = response.nodeId, nodeKey = existingKey)
            }

            IdentifyNodeResponse.IdentifyStatus.PENDING -> {
                applyRuntimeSettings(response.runtimeSettings)
                log.info("Node ${response.nodeId} is PENDING — awaiting admin approval")
                NodeIdentity(nodeId = response.nodeId, nodeKey = existingKey)
            }

            else -> throw NodeRejectedException("Node ${response.nodeId} was REJECTED by master")
        }
    }

    /**
     * Applies the snapshot from a register/identify response. An all-zero message means the master
     * predates runtime settings (or the field is absent), so the cached/default values are kept.
     */
    private fun applyRuntimeSettings(proto: AgentRuntimeSettings) {
        val settings = RuntimeSettings.fromProto(proto) ?: run {
            log.debug("Master sent no runtime settings snapshot — keeping current values")
            return
        }
        runtimeSettings.apply(settings)
        log.info("Applied runtime settings from master: {}", settings)
    }

    private fun resolvePublicIp(): String {
        config.publicIpOverride.takeIf { it.isNotBlank() }
            ?.let { return it }
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

    private fun resolvePrivateIp(): String = config.privateIpOverride.takeIf { it.isNotBlank() }
        ?: fetchPrivateIpFromMaster()
        ?: runCatching { InetAddress.getLocalHost().hostAddress }.getOrElse { "unknown" }

    private fun fetchPrivateIpFromMaster(): String? = runCatching {
        val url = URI("http://${config.masterAddress}:${config.masterHttpPort}/api/nodes/my-ip").toURL()
        val conn = url.openConnection()
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.getInputStream()
            .bufferedReader()
            .readText()
            .trim()
            .takeIf { it.isNotBlank() }
    }.getOrNull()
}
