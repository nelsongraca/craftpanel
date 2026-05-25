package io.craftpanel.master.grpc

import com.craftpanel.agent.v1.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory

class DataServiceImpl : DataServiceGrpcKt.DataServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(DataServiceImpl::class.java)

    override fun console(requests: Flow<ConsoleInput>): Flow<ConsoleOutput> = flow {
        // TODO: proxy to the active agent DataService channel for the target server
        log.debug("Console stream opened")
        requests.collect { input ->
            log.debug("Console input for server ${input.serverId}: ${input.data.size()} bytes")
        }
    }

    override suspend fun listFiles(request: ListFilesRequest): ListFilesResponse {
        // TODO: proxy to agent
        return listFilesResponse { }
    }

    override suspend fun readFile(request: ReadFileRequest): ReadFileResponse {
        // TODO: proxy to agent
        return readFileResponse { }
    }

    override suspend fun writeFile(request: WriteFileRequest): WriteFileResponse {
        // TODO: proxy to agent
        return writeFileResponse { success = false }
    }

    override suspend fun deleteFile(request: DeleteFileRequest): DeleteFileResponse {
        // TODO: proxy to agent
        return deleteFileResponse { success = false }
    }

    override suspend fun makeDirectory(request: MakeDirectoryRequest): MakeDirectoryResponse {
        // TODO: proxy to agent
        return makeDirectoryResponse { success = false }
    }

    override suspend fun moveFile(request: MoveFileRequest): MoveFileResponse {
        // TODO: proxy to agent
        return moveFileResponse { success = false }
    }

    override suspend fun copyFile(request: CopyFileRequest): CopyFileResponse {
        // TODO: proxy to agent
        return copyFileResponse { success = false }
    }

    override suspend fun uploadFile(requests: Flow<UploadFileChunk>): UploadFileResponse {
        // TODO: proxy chunked upload to agent
        return uploadFileResponse { success = false }
    }

    override fun downloadFile(request: DownloadFileRequest): Flow<DownloadFileChunk> = flow {
        // TODO: proxy chunked download from agent
    }
}
