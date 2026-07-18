package io.craftpanel.master.grpc.handlers

import io.craftpanel.master.grpc.DataOpContext
import io.craftpanel.proto.AgentMessage
import io.craftpanel.proto.ConsoleOutput
import org.slf4j.LoggerFactory

class DataOpResponseHandler(private val context: DataOpContext) {

    private val log = LoggerFactory.getLogger(DataOpResponseHandler::class.java)

    fun routeUnaryResponse(nodeId: String, requestId: String, msg: AgentMessage) {
        context.pendingRequests.remove("$nodeId/$requestId")
            ?.complete(msg)
    }

    fun routeConsoleOutput(nodeId: String, output: ConsoleOutput) {
        val key = "$nodeId/${output.requestId}"
        context.consoleOutputChannels[key]?.trySend(output)
        if (output.closed) {
            context.consoleOutputChannels.remove(key)
                ?.close()
        }
    }

    fun handle(msg: AgentMessage, nodeId: String) {
        when {
            msg.hasConsoleOutput() -> routeConsoleOutput(nodeId, msg.consoleOutput)
            msg.hasListFilesResponse() -> routeUnaryResponse(nodeId, msg.listFilesResponse.requestId, msg)
            msg.hasReadFileResponse() -> routeUnaryResponse(nodeId, msg.readFileResponse.requestId, msg)
            msg.hasWriteFileResponse() -> routeUnaryResponse(nodeId, msg.writeFileResponse.requestId, msg)
            msg.hasDeleteFileResponse() -> routeUnaryResponse(nodeId, msg.deleteFileResponse.requestId, msg)
            msg.hasMakeDirectoryResponse() -> routeUnaryResponse(nodeId, msg.makeDirectoryResponse.requestId, msg)
            msg.hasMoveFileResponse() -> routeUnaryResponse(nodeId, msg.moveFileResponse.requestId, msg)
            msg.hasCopyFileResponse() -> routeUnaryResponse(nodeId, msg.copyFileResponse.requestId, msg)
            msg.hasDownloadFileResponse() -> routeUnaryResponse(nodeId, msg.downloadFileResponse.requestId, msg)
            msg.hasUploadFileResponse() -> routeUnaryResponse(nodeId, msg.uploadFileResponse.requestId, msg)
            msg.hasFetchContainerLogsResponse() -> routeUnaryResponse(nodeId, msg.fetchContainerLogsResponse.requestId, msg)
            else -> log.debug("DataOpResponseHandler: unhandled message type for node $nodeId")
        }
    }
}
