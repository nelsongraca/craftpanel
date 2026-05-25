package io.craftpanel.master.routes

import com.craftpanel.agent.v1.MasterMessage
import com.craftpanel.agent.v1.masterMessage
import com.craftpanel.agent.v1.shutdownCommand
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.database.schema.NodeMetrics
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.util.toKotlinUuid
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

@Serializable
data class NodeResponse(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val hostname: String,
    @SerialName("public_ip") val publicIp: String,
    @SerialName("private_ip") val privateIp: String,
    val status: String,
    @SerialName("total_ram_mb") val totalRamMb: Int,
    @SerialName("total_cpu_shares") val totalCpuShares: Int,
    @SerialName("allocated_ram_mb") val allocatedRamMb: Int,
    @SerialName("allocated_cpu_shares") val allocatedCpuShares: Int,
    @SerialName("port_range_start") val portRangeStart: Int,
    @SerialName("port_range_end") val portRangeEnd: Int,
    @SerialName("data_path") val dataPath: String,
    @SerialName("agent_version") val agentVersion: String?,
    @SerialName("last_seen_at") val lastSeenAt: String?,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class PatchNodeRequest(
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("port_range_start") val portRangeStart: Int? = null,
    @SerialName("port_range_end") val portRangeEnd: Int? = null,
    @SerialName("data_path") val dataPath: String? = null,
)

@Serializable
data class NodeMetricsResponse(
    val timestamps: List<String>,
    @SerialName("cpu_percent") val cpuPercent: List<Double>,
    @SerialName("ram_used_mb") val ramUsedMb: List<Int>,
    @SerialName("ram_total_mb") val ramTotalMb: List<Int>,
    @SerialName("net_in_bytes") val netInBytes: List<Long>,
    @SerialName("net_out_bytes") val netOutBytes: List<Long>,
    @SerialName("disk_used_bytes") val diskUsedBytes: List<Long>,
    @SerialName("disk_total_bytes") val diskTotalBytes: List<Long>,
)

private data class NodeAllocations(val ramMb: Int, val cpuShares: Int)

private fun allocationsForNode(nodeKotlinId: kotlin.uuid.Uuid): NodeAllocations = transaction {
    val rows = Servers.selectAll().where { Servers.nodeId eq nodeKotlinId }
    var ram = 0
    var cpu = 0
    for (row in rows) {
        ram += row[Servers.memoryMb]
        cpu += row[Servers.cpuShares]
    }
    NodeAllocations(ram, cpu)
}

fun Route.nodesRoutes(sendToNode: (String, MasterMessage) -> Boolean) {
    authenticate("auth-jwt") {
        route("/api/v1/nodes") {

            get {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.nodes")) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("message" to "Insufficient permissions"))
                    return@get
                }

                val nodes = transaction {
                    Nodes.selectAll().map { row ->
                        val nodeKotlinId = row[Nodes.id]
                        val alloc = allocationsForNode(nodeKotlinId)
                        NodeResponse(
                            id = nodeKotlinId.toString(),
                            displayName = row[Nodes.displayName],
                            hostname = row[Nodes.hostname],
                            publicIp = row[Nodes.publicIp],
                            privateIp = row[Nodes.privateIp],
                            status = row[Nodes.status],
                            totalRamMb = row[Nodes.totalRamMb],
                            totalCpuShares = row[Nodes.totalCpuShares],
                            allocatedRamMb = alloc.ramMb,
                            allocatedCpuShares = alloc.cpuShares,
                            portRangeStart = row[Nodes.portRangeStart],
                            portRangeEnd = row[Nodes.portRangeEnd],
                            dataPath = row[Nodes.dataPath],
                            agentVersion = row[Nodes.agentVersion],
                            lastSeenAt = row[Nodes.lastSeenAt]?.toString(),
                            createdAt = row[Nodes.createdAt].toString(),
                        )
                    }
                }
                call.respond(nodes)
            }

            get("/{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.nodes")) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("message" to "Insufficient permissions"))
                    return@get
                }

                val id = parseNodeId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid node ID"))
                    return@get
                }

                val node = transaction {
                    Nodes.selectAll().where { Nodes.id eq id }.firstOrNull()?.let { row ->
                        val alloc = allocationsForNode(id)
                        NodeResponse(
                            id = row[Nodes.id].toString(),
                            displayName = row[Nodes.displayName],
                            hostname = row[Nodes.hostname],
                            publicIp = row[Nodes.publicIp],
                            privateIp = row[Nodes.privateIp],
                            status = row[Nodes.status],
                            totalRamMb = row[Nodes.totalRamMb],
                            totalCpuShares = row[Nodes.totalCpuShares],
                            allocatedRamMb = alloc.ramMb,
                            allocatedCpuShares = alloc.cpuShares,
                            portRangeStart = row[Nodes.portRangeStart],
                            portRangeEnd = row[Nodes.portRangeEnd],
                            dataPath = row[Nodes.dataPath],
                            agentVersion = row[Nodes.agentVersion],
                            lastSeenAt = row[Nodes.lastSeenAt]?.toString(),
                            createdAt = row[Nodes.createdAt].toString(),
                        )
                    }
                } ?: run {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "Node not found"))
                    return@get
                }
                call.respond(node)
            }

            post("/{id}/trust") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.nodes")) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("message" to "Insufficient permissions"))
                    return@post
                }

                val id = parseNodeId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid node ID"))
                    return@post
                }

                val updated = transaction { Nodes.update({ Nodes.id eq id }) { it[status] = "ACTIVE" } }
                if (updated == 0) {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "Node not found"))
                    return@post
                }
                call.respond(HttpStatusCode.NoContent)
            }

            post("/{id}/reject") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.nodes")) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("message" to "Insufficient permissions"))
                    return@post
                }

                val id = parseNodeId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid node ID"))
                    return@post
                }

                val updated = transaction { Nodes.update({ Nodes.id eq id }) { it[status] = "REJECTED" } }
                if (updated == 0) {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "Node not found"))
                    return@post
                }
                call.respond(HttpStatusCode.NoContent)
            }

            post("/{id}/token/rotate") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.nodes")) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("message" to "Insufficient permissions"))
                    return@post
                }

                val id = parseNodeId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid node ID"))
                    return@post
                }

                val (rawKey, updated) = transaction {
                    val exists = Nodes.selectAll().where { Nodes.id eq id }.firstOrNull() != null
                    if (!exists) return@transaction null to 0

                    val raw = generateNodeKey()
                    val hash = sha256Hex(raw)
                    val rows = Nodes.update({ Nodes.id eq id }) { it[tokenHash] = hash }
                    raw to rows
                }

                if (updated == 0) {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "Node not found"))
                    return@post
                }
                call.respond(mapOf("node_key" to rawKey!!))
            }

            post("/{id}/shutdown") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.nodes")) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("message" to "Insufficient permissions"))
                    return@post
                }

                val id = parseNodeId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid node ID"))
                    return@post
                }

                val exists = transaction { Nodes.selectAll().where { Nodes.id eq id }.firstOrNull() != null }
                if (!exists) {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "Node not found"))
                    return@post
                }

                val msg = masterMessage { shutdown = shutdownCommand { timeoutSeconds = 30 } }
                val sent = sendToNode(id.toString(), msg)
                if (!sent) {
                    call.respond(HttpStatusCode.BadGateway, mapOf("message" to "Agent not connected"))
                    return@post
                }
                call.respond(HttpStatusCode.Accepted, mapOf("message" to "Shutdown command sent"))
            }

            patch("/{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.nodes")) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("message" to "Insufficient permissions"))
                    return@patch
                }

                val id = parseNodeId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid node ID"))
                    return@patch
                }

                val req = call.receive<PatchNodeRequest>()

                val result = transaction {
                    val current = Nodes.selectAll().where { Nodes.id eq id }.firstOrNull()
                        ?: return@transaction null

                    val newStart = req.portRangeStart ?: current[Nodes.portRangeStart]
                    val newEnd = req.portRangeEnd ?: current[Nodes.portRangeEnd]

                    if (newStart >= newEnd) return@transaction "Port range start must be less than end"

                    Nodes.update({ Nodes.id eq id }) {
                        if (req.displayName != null) it[displayName] = req.displayName
                        it[portRangeStart] = newStart
                        it[portRangeEnd] = newEnd
                        if (req.dataPath != null) it[dataPath] = req.dataPath
                    }
                    "ok"
                }

                when (result) {
                    null -> call.respond(HttpStatusCode.NotFound, mapOf("message" to "Node not found"))
                    "ok" -> call.respond(HttpStatusCode.NoContent)
                    else -> call.respond(HttpStatusCode.UnprocessableEntity, mapOf("message" to result))
                }
            }

            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.nodes")) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("message" to "Insufficient permissions"))
                    return@delete
                }

                val id = parseNodeId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid node ID"))
                    return@delete
                }

                val updated = transaction { Nodes.update({ Nodes.id eq id }) { it[status] = "DECOMMISSIONED" } }
                if (updated == 0) {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "Node not found"))
                    return@delete
                }
                call.respond(HttpStatusCode.NoContent)
            }

            get("/{id}/metrics") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "system.nodes")) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("message" to "Insufficient permissions"))
                    return@get
                }

                val id = parseNodeId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid node ID"))
                    return@get
                }

                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 360) ?: 60

                val exists = transaction { Nodes.selectAll().where { Nodes.id eq id }.firstOrNull() != null }
                if (!exists) {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "Node not found"))
                    return@get
                }

                val metrics = transaction {
                    NodeMetrics.selectAll()
                        .where { NodeMetrics.nodeId eq id }
                        .orderBy(NodeMetrics.recordedAt, SortOrder.DESC)
                        .limit(limit)
                        .toList()
                        .reversed()
                }

                call.respond(
                    NodeMetricsResponse(
                        timestamps = metrics.map { it[NodeMetrics.recordedAt].toString() },
                        cpuPercent = metrics.map { it[NodeMetrics.cpuPercent] },
                        ramUsedMb = metrics.map { it[NodeMetrics.ramUsedMb] },
                        ramTotalMb = metrics.map { it[NodeMetrics.ramTotalMb] },
                        netInBytes = metrics.map { it[NodeMetrics.netInBytes] },
                        netOutBytes = metrics.map { it[NodeMetrics.netOutBytes] },
                        diskUsedBytes = metrics.map { it[NodeMetrics.diskUsedBytes] },
                        diskTotalBytes = metrics.map { it[NodeMetrics.diskTotalBytes] },
                    )
                )
            }
        }
    }
}

private fun parseNodeId(raw: String?): kotlin.uuid.Uuid? =
    raw?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }

private fun generateNodeKey(): String {
    val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun sha256Hex(input: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}
