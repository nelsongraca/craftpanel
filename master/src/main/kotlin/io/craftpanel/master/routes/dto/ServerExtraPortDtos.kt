package io.craftpanel.master.routes.dto

import io.craftpanel.master.service.repo.ServerExtraPortRow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerExtraPortResponse(
    val id: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("node_id") val nodeId: String,
    val name: String,
    @SerialName("container_port") val containerPort: Int,
    @SerialName("host_port") val hostPort: Int,
    val protocol: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

fun ServerExtraPortRow.toResponse(): ServerExtraPortResponse = ServerExtraPortResponse(
    id = id.toString(),
    serverId = serverId.toString(),
    nodeId = nodeId.toString(),
    name = name,
    containerPort = containerPort,
    hostPort = hostPort,
    protocol = protocol,
    createdAt = createdAt,
    updatedAt = updatedAt
)

@Serializable
data class CreateServerExtraPortRequest(val name: String, @SerialName("container_port") val containerPort: Int, @SerialName("host_port") val hostPort: Int? = null, val protocol: String = "TCP")

@Serializable
data class ServerPortsResponse(@SerialName("primary_port") val primaryPort: PrimaryPortInfo, @SerialName("extra_ports") val extraPorts: List<ServerExtraPortResponse>)

@Serializable
data class PrimaryPortInfo(@SerialName("host_port") val hostPort: Int, @SerialName("container_port") val containerPort: Int, val protocol: String)
