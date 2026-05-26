package io.craftpanel.master.routes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(val message: String)

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class NodeKeyResponse(@SerialName("node_key") val nodeKey: String)

@Serializable
data class IdResponse(val id: String)
