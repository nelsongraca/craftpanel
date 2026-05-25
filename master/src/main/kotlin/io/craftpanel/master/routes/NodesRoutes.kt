package io.craftpanel.master.routes

import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.util.toKotlinUuid
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

@Serializable
data class NodeResponse(
    val id: String,
    val displayName: String,
    val hostname: String,
    val publicIp: String,
    val status: String,
    val totalRamMb: Int,
    val totalCpuShares: Int,
)

fun Route.nodesRoutes() {
    authenticate("auth-jwt") {
        route("/api/v1/nodes") {
            get {
                // TODO: permission check — system.nodes
                val nodes = transaction {
                    Nodes.selectAll().map {
                        NodeResponse(
                            id = it[Nodes.id].toString(),
                            displayName = it[Nodes.displayName],
                            hostname = it[Nodes.hostname],
                            publicIp = it[Nodes.publicIp],
                            status = it[Nodes.status],
                            totalRamMb = it[Nodes.totalRamMb],
                            totalCpuShares = it[Nodes.totalCpuShares],
                        )
                    }
                }
                call.respond(nodes)
            }

            put("/{id}/approve") {
                // TODO: permission check — system.nodes
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }
                    ?: run { call.respond(HttpStatusCode.BadRequest); return@put }

                transaction { Nodes.update({ Nodes.id eq id }) { it[status] = "ACTIVE" } }
                call.respond(HttpStatusCode.NoContent)
            }

            put("/{id}/reject") {
                // TODO: permission check — system.nodes
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }
                    ?: run { call.respond(HttpStatusCode.BadRequest); return@put }

                transaction { Nodes.update({ Nodes.id eq id }) { it[status] = "REJECTED" } }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
