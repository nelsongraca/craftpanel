package io.craftpanel.master.grpc

import com.craftpanel.agent.v1.*
import io.craftpanel.master.config.NodeConfig
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.util.toKotlinUuid
import io.grpc.ManagedChannel
import io.grpc.netty.NettyChannelBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class DataServiceProxy(private val nodeConfig: NodeConfig) {

    private val log = LoggerFactory.getLogger(DataServiceProxy::class.java)
    private val channels = ConcurrentHashMap<String, ManagedChannel>()

    private fun channelFor(nodeId: String, privateIp: String): ManagedChannel =
        channels.getOrPut(nodeId) {
            log.debug("Opening DataService channel to $privateIp:${nodeConfig.agentDataPort}")
            NettyChannelBuilder.forAddress(privateIp, nodeConfig.agentDataPort)
                .usePlaintext()
                .build()
        }

    fun closeChannel(nodeId: String) {
        channels.remove(nodeId)
            ?.shutdown()
    }

    fun closeAll() {
        channels.values.forEach { it.shutdown() }
        channels.clear()
    }

    private data class ServerNode(val nodeId: String, val privateIp: String, val networkId: UUID?)

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
        )
    }

    private fun stubFor(nodeId: String, privateIp: String) =
        DataServiceGrpcKt.DataServiceCoroutineStub(channelFor(nodeId, privateIp))

    // ── Console ──────────────────────────────────────────────────────────────

    fun console(serverId: String, input: Flow<ConsoleInput>): Flow<ConsoleOutput> = flow {
        val sn = lookupServerNode(serverId)
            ?: error("Server $serverId not found")
        emitAll(stubFor(sn.nodeId, sn.privateIp).console(input))
    }

    // ── File operations ───────────────────────────────────────────────────────

    suspend fun listFiles(serverId: String, path: String): ListFilesResponse {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        return stubFor(sn.nodeId, sn.privateIp).listFiles(listFilesRequest {
            this.serverId = serverId; this.path = path
        })
    }

    suspend fun readFile(serverId: String, path: String): ReadFileResponse {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        return stubFor(sn.nodeId, sn.privateIp).readFile(readFileRequest {
            this.serverId = serverId; this.path = path
        })
    }

    suspend fun writeFile(serverId: String, path: String, content: ByteArray): WriteFileResponse {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        return stubFor(sn.nodeId, sn.privateIp).writeFile(writeFileRequest {
            this.serverId = serverId; this.path = path
            this.content = com.google.protobuf.ByteString.copyFrom(content)
        })
    }

    suspend fun deleteFile(serverId: String, path: String, recursive: Boolean): DeleteFileResponse {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        return stubFor(sn.nodeId, sn.privateIp).deleteFile(deleteFileRequest {
            this.serverId = serverId; this.path = path; this.recursive = recursive
        })
    }

    suspend fun makeDirectory(serverId: String, path: String): MakeDirectoryResponse {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        return stubFor(sn.nodeId, sn.privateIp).makeDirectory(makeDirectoryRequest {
            this.serverId = serverId; this.path = path
        })
    }

    suspend fun moveFile(serverId: String, sourcePath: String, destinationPath: String): MoveFileResponse {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        return stubFor(sn.nodeId, sn.privateIp).moveFile(moveFileRequest {
            this.serverId = serverId; this.sourcePath = sourcePath; this.destinationPath = destinationPath
        })
    }

    suspend fun copyFile(serverId: String, sourcePath: String, destinationPath: String, recursive: Boolean): CopyFileResponse {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        return stubFor(sn.nodeId, sn.privateIp).copyFile(copyFileRequest {
            this.serverId = serverId; this.sourcePath = sourcePath; this.destinationPath = destinationPath
            this.recursive = recursive
        })
    }

    suspend fun uploadFile(serverId: String, path: String, chunks: Flow<UploadFileChunk>): UploadFileResponse {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        return stubFor(sn.nodeId, sn.privateIp).uploadFile(chunks)
    }

    fun downloadFile(serverId: String, path: String): Flow<DownloadFileChunk> = flow {
        val sn = lookupServerNode(serverId) ?: error("Server $serverId not found")
        emitAll(stubFor(sn.nodeId, sn.privateIp).downloadFile(downloadFileRequest {
            this.serverId = serverId; this.path = path
        }))
    }
}
