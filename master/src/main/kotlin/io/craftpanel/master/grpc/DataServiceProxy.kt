package io.craftpanel.master.grpc

import com.craftpanel.agent.v1.*
import com.google.protobuf.ByteString
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.util.toKotlinUuid
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.netty.GrpcSslContexts
import io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class DataServiceProxy(private val nodeConfig: NodeConfig, private val profile: String = "prod") {

    private val log = LoggerFactory.getLogger(DataServiceProxy::class.java)
    private val channels = ConcurrentHashMap<String, ManagedChannel>()

    companion object {

        val DATA_TOKEN_KEY: Metadata.Key<String> =
            Metadata.Key.of("x-craftpanel-data-token", Metadata.ASCII_STRING_MARSHALLER)
    }

    private fun channelFor(nodeId: String, privateIp: String): ManagedChannel =
        channels.getOrPut(nodeId) {
            log.debug("Opening DataService channel to $privateIp:${nodeConfig.agentDataPort}")
            val builder = NettyChannelBuilder.forAddress(privateIp, nodeConfig.agentDataPort)
            if (nodeConfig.agentTlsEnabled) {
                val sslContext = GrpcSslContexts.forClient()
                    .trustManager(File(nodeConfig.agentTlsTrustCertPath))
                    .build()
                builder.useTransportSecurity()
                    .sslContext(sslContext)
            }
            else {
                check(profile == "dev") {
                    "Agent TLS trust cert is required outside dev profile — set NODE_AGENT_TLS_TRUST_CERT"
                }
                builder.usePlaintext()
            }
            builder.build()
        }

    fun closeNode(nodeId: String) {
        channels.remove(nodeId)
            ?.shutdown()
    }

    fun closeAll() {
        channels.values.forEach { it.shutdown() }
        channels.clear()
    }

    private data class ServerNode(val nodeId: String, val privateIp: String, val networkId: UUID?, val dataToken: String?)

    private fun lookupServerNode(serverId: String): ServerNode? = transaction {
        val id = runCatching {
            UUID.fromString(serverId)
                .toKotlinUuid()
        }.getOrNull() ?: return@transaction null
        val server = Servers.selectAll()
            .where { Servers.id eq id }
            .firstOrNull() ?: return@transaction null
        val nodeKotlinId = server[Servers.nodeId]
        val node = Nodes.selectAll()
            .where { Nodes.id eq nodeKotlinId }
            .firstOrNull() ?: return@transaction null
        ServerNode(
            nodeId = nodeKotlinId.toString(),
            privateIp = node[Nodes.privateIp],
            networkId = server[Servers.networkId]?.let { UUID.fromString(it.toString()) },
            dataToken = node[Nodes.dataToken],
        )
    }

    private fun stubFor(nodeId: String, privateIp: String, dataToken: String?): DataServiceGrpcKt.DataServiceCoroutineStub {
        val stub = DataServiceGrpcKt.DataServiceCoroutineStub(channelFor(nodeId, privateIp))
        if (dataToken.isNullOrBlank()) return stub
        val md = Metadata()
        md.put(DATA_TOKEN_KEY, dataToken)
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md))
    }

    // ── Console ──────────────────────────────────────────────────────────────

    fun console(serverId: String, input: Flow<ConsoleInput>): Flow<ConsoleOutput> = flow {
        val sn = lookupServerNode(serverId)
            ?: error("Server $serverId not found")
        emitAll(stubFor(sn.nodeId, sn.privateIp, sn.dataToken).console(input))
    }

    // ── File operations ───────────────────────────────────────────────────────

    suspend fun listFiles(serverId: String, path: String): ListFilesResponse {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        return stubFor(sn.nodeId, sn.privateIp, sn.dataToken).listFiles(listFilesRequest {
            this.serverId = serverId; this.path = path
        })
    }

    suspend fun readFile(serverId: String, path: String): ReadFileResponse {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        return stubFor(sn.nodeId, sn.privateIp, sn.dataToken).readFile(readFileRequest {
            this.serverId = serverId; this.path = path
        })
    }

    suspend fun writeFile(serverId: String, path: String, content: ByteArray): WriteFileResponse {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        return stubFor(sn.nodeId, sn.privateIp, sn.dataToken).writeFile(writeFileRequest {
            this.serverId = serverId; this.path = path
            this.content = ByteString.copyFrom(content)
        })
    }

    suspend fun deleteFile(serverId: String, path: String, recursive: Boolean): DeleteFileResponse {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        return stubFor(sn.nodeId, sn.privateIp, sn.dataToken).deleteFile(deleteFileRequest {
            this.serverId = serverId; this.path = path; this.recursive = recursive
        })
    }

    suspend fun makeDirectory(serverId: String, path: String): MakeDirectoryResponse {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        return stubFor(sn.nodeId, sn.privateIp, sn.dataToken).makeDirectory(makeDirectoryRequest {
            this.serverId = serverId; this.path = path
        })
    }

    suspend fun moveFile(serverId: String, sourcePath: String, destinationPath: String): MoveFileResponse {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        return stubFor(sn.nodeId, sn.privateIp, sn.dataToken).moveFile(moveFileRequest {
            this.serverId = serverId; this.sourcePath = sourcePath; this.destinationPath = destinationPath
        })
    }

    suspend fun copyFile(serverId: String, sourcePath: String, destinationPath: String, recursive: Boolean): CopyFileResponse {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        return stubFor(sn.nodeId, sn.privateIp, sn.dataToken).copyFile(copyFileRequest {
            this.serverId = serverId; this.sourcePath = sourcePath; this.destinationPath = destinationPath
            this.recursive = recursive
        })
    }

    suspend fun uploadFile(serverId: String, chunks: Flow<UploadFileChunk>): UploadFileResponse {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        return stubFor(sn.nodeId, sn.privateIp, sn.dataToken).uploadFile(chunks)
    }

    fun downloadFile(serverId: String, path: String): Flow<DownloadFileChunk> = flow {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        emitAll(stubFor(sn.nodeId, sn.privateIp, sn.dataToken).downloadFile(downloadFileRequest {
            this.serverId = serverId; this.path = path
        }))
    }
}
