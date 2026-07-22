package io.craftpanel.master.routes

import io.github.smiley4.ktoropenapi.config.ResponseConfig
import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeFully
import io.swagger.v3.oas.models.media.Schema
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class ErrorResponse(val message: String)

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class NodeKeyResponse(@SerialName("node_key") val nodeKey: String)

fun ApplicationCall.userId(): Uuid = Uuid.parse(principal<JWTPrincipal>()!!.payload.subject)

/**
 * Streams [flow]'s chunks straight to the response channel as they arrive, instead of
 * buffering the whole payload into memory. Response is committed as [ContentType.Application.OctetStream].
 */
suspend fun ApplicationCall.respondBinaryFlow(flow: Flow<ByteArray>) {
    respondBytesWriter(contentType = ContentType.Application.OctetStream) {
        flow.collect { chunk -> writeFully(chunk) }
    }
}

/** OpenAPI doc-block response body for an endpoint whose handler calls [respondBinaryFlow]. */
fun ResponseConfig.binaryFileBody() {
    body(
        Schema<Any>().apply {
            type = "string"
            format = "binary"
        }
    ) {
        mediaTypes = listOf(ContentType.Application.OctetStream)
    }
}
