package io.craftpanel.master.routes

import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.util.toKotlinUuid
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

@Serializable
data class NetworkResponse(
    val id: String,
    val name: String,
    val type: String,
    @SerialName("proxy_type") val proxyType: String?,
    @SerialName("proxy_port") val proxyPort: Int?,
    val description: String?,
    @SerialName("server_count") val serverCount: Int,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class NetworkServerItem(
    val id: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("server_type") val serverType: String,
    val status: String,
)

@Serializable
data class NetworkDetailResponse(
    val id: String,
    val name: String,
    val type: String,
    @SerialName("proxy_type") val proxyType: String?,
    @SerialName("proxy_port") val proxyPort: Int?,
    val description: String?,
    @SerialName("server_count") val serverCount: Int,
    val servers: List<NetworkServerItem>,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class CreateNetworkRequest(
    val name: String,
    val type: String,
    @SerialName("proxy_type") val proxyType: String? = null,
    @SerialName("proxy_port") val proxyPort: Int? = null,
    val description: String? = null,
)

@Serializable
data class PatchNetworkRequest(
    val name: String? = null,
    val description: String? = null,
)

fun Route.networksRoutes() {
    authenticate("auth-jwt") {
        route("/api/v1/networks") {

            get("", {
                summary = "List networks"
                response {
                    code(HttpStatusCode.OK) { body<List<NetworkResponse>>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "server.view")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@get
                }

                val networks = transaction {
                    val counts = Servers.selectAll()
                        .groupBy({ it[Servers.networkId] }, { 1 })
                        .mapValues { (_, v) -> v.size }

                    ServerNetworks.selectAll().map { row ->
                        val netId = row[ServerNetworks.id]
                        NetworkResponse(
                            id = netId.toString(),
                            name = row[ServerNetworks.name],
                            type = row[ServerNetworks.type],
                            proxyType = row[ServerNetworks.proxyType],
                            proxyPort = row[ServerNetworks.proxyPort],
                            description = row[ServerNetworks.description],
                            serverCount = counts[netId] ?: 0,
                            createdAt = row[ServerNetworks.createdAt].toString(),
                        )
                    }
                }
                call.respond(networks)
            }

            post("", {
                summary = "Create network"
                request { body<CreateNetworkRequest>() }
                response {
                    code(HttpStatusCode.Created) { body<NetworkResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "server.create")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@post
                }

                val req = call.receive<CreateNetworkRequest>()

                val nameTaken = transaction {
                    ServerNetworks.selectAll().where { ServerNetworks.name eq req.name }.firstOrNull() != null
                }
                if (nameTaken) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Network name already taken"))
                    return@post
                }

                val network = transaction {
                    val insertedId = ServerNetworks.insert {
                        it[name] = req.name
                        it[type] = req.type
                        it[proxyType] = req.proxyType
                        it[proxyPort] = req.proxyPort
                        it[description] = req.description
                    }[ServerNetworks.id]
                    val row = ServerNetworks.selectAll().where { ServerNetworks.id eq insertedId }.first()
                    NetworkResponse(
                        id = insertedId.toString(),
                        name = row[ServerNetworks.name],
                        type = row[ServerNetworks.type],
                        proxyType = row[ServerNetworks.proxyType],
                        proxyPort = row[ServerNetworks.proxyPort],
                        description = row[ServerNetworks.description],
                        serverCount = 0,
                        createdAt = row[ServerNetworks.createdAt].toString(),
                    )
                }
                call.respond(HttpStatusCode.Created, network)
            }

            get("/{id}", {
                summary = "Get network"
                response {
                    code(HttpStatusCode.OK) { body<NetworkDetailResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "server.view")) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@get
                }

                val id = parseNetworkId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid network ID"))
                    return@get
                }

                val detail = transaction {
                    val row = ServerNetworks.selectAll()
                        .where { ServerNetworks.id eq id }
                        .firstOrNull() ?: return@transaction null

                    val members = Servers.selectAll()
                        .where { Servers.networkId eq id }
                        .map { s ->
                            NetworkServerItem(
                                id = s[Servers.id].toString(),
                                displayName = s[Servers.displayName],
                                serverType = s[Servers.serverType],
                                status = s[Servers.status],
                            )
                        }

                    NetworkDetailResponse(
                        id = row[ServerNetworks.id].toString(),
                        name = row[ServerNetworks.name],
                        type = row[ServerNetworks.type],
                        proxyType = row[ServerNetworks.proxyType],
                        proxyPort = row[ServerNetworks.proxyPort],
                        description = row[ServerNetworks.description],
                        serverCount = members.size,
                        servers = members,
                        createdAt = row[ServerNetworks.createdAt].toString(),
                    )
                } ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Network not found"))
                    return@get
                }
                call.respond(detail)
            }

            patch("/{id}", {
                summary = "Update network"
                request { body<PatchNetworkRequest>() }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)

                val id = parseNetworkId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid network ID"))
                    return@patch
                }

                val networkIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, "server.configure", networkId = networkIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@patch
                }

                val req = call.receive<PatchNetworkRequest>()

                // null → conflict signal; false → not found; true → ok
                val result: Boolean? = transaction {
                    val exists = ServerNetworks.selectAll().where { ServerNetworks.id eq id }.firstOrNull() != null
                    if (!exists) return@transaction false

                    if (req.name != null) {
                        val nameTaken = ServerNetworks.selectAll()
                            .where { (ServerNetworks.name eq req.name) and (ServerNetworks.id neq id) }
                            .firstOrNull() != null
                        if (nameTaken) return@transaction null
                    }

                    ServerNetworks.update({ ServerNetworks.id eq id }) {
                        if (req.name != null) it[name] = req.name
                        if (req.description != null) it[description] = req.description
                    }
                    true
                }

                when (result) {
                    null -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Network name already taken"))
                    false -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Network not found"))
                    else -> call.respond(HttpStatusCode.NoContent)
                }
            }

            delete("/{id}", {
                summary = "Delete network"
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)

                val id = parseNetworkId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid network ID"))
                    return@delete
                }

                val networkIdJava = UUID.fromString(id.toString())
                if (!PermissionResolver.hasPermission(userId, "server.delete", networkId = networkIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@delete
                }

                val deleted = transaction {
                    val exists = ServerNetworks.selectAll().where { ServerNetworks.id eq id }.firstOrNull() != null
                    if (!exists) return@transaction false
                    Servers.update({ Servers.networkId eq id }) { it[networkId] = null }
                    ServerNetworks.deleteWhere { ServerNetworks.id eq id }
                    true
                }

                if (!deleted) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Network not found"))
                    return@delete
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun parseNetworkId(raw: String?): kotlin.uuid.Uuid? =
    raw?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }
