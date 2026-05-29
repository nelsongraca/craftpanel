package io.craftpanel.master.routes

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class ErrorResponse(val message: String)

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class NodeKeyResponse(@SerialName("node_key") val nodeKey: String)

fun ApplicationCall.userId(): UUID = UUID.fromString(principal<JWTPrincipal>()!!.payload.subject)
