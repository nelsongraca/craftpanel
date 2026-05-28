package io.craftpanel.master.routes

import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ErrorResponse(val message: String)

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class NodeKeyResponse(@SerialName("node_key") val nodeKey: String)

@Serializable
data class IdResponse(val id: String)

fun ApplicationCall.userId(): UUID = UUID.fromString(principal<JWTPrincipal>()!!.payload.subject)
