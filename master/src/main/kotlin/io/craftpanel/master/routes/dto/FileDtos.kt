package io.craftpanel.master.routes.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FileEntryResponse(
    val name: String,
    @SerialName("is_directory") val isDirectory: Boolean,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("modified_at") val modifiedAt: String?,
    val permissions: String
)

@Serializable
data class ListFilesResponse(val path: String, val entries: List<FileEntryResponse>)

@Serializable
data class ReadFileResponse(val path: String, val content: String, val encoding: String)

@Serializable
data class ConsoleLogsResponse(val lines: List<String>)

@Serializable
data class UploadResponse(val path: String, @SerialName("size_bytes") val sizeBytes: Long)

@Serializable
data class MoveRequest(@SerialName("source_path") val sourcePath: String, @SerialName("destination_path") val destinationPath: String)

@Serializable
data class CopyRequest(@SerialName("source_path") val sourcePath: String, @SerialName("destination_path") val destinationPath: String, val recursive: Boolean = false)

@Serializable
data class MkdirRequest(val path: String)
