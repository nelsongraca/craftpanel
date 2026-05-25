package io.craftpanel.master.routes

import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.database.schema.GroupPermissions
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.PortRegistry
import io.craftpanel.master.database.schema.ServerMigrations
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
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
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

@Serializable
data class ServerResponse(
    val id: String,
    val name: String,
    @SerialName("display_name") val displayName: String,
    val description: String?,
    @SerialName("server_type") val serverType: String,
    val status: String,
    @SerialName("node_id") val nodeId: String,
    @SerialName("network_id") val networkId: String?,
    @SerialName("game_port") val gamePort: Int,
    @SerialName("memory_mb") val memoryMb: Int,
    @SerialName("cpu_shares") val cpuShares: Int,
    @SerialName("exposed_externally") val exposedExternally: Boolean,
    @SerialName("public_subdomain") val publicSubdomain: String?,
    @SerialName("is_migrating") val isMigrating: Boolean,
    @SerialName("config_mode") val configMode: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class CreateServerRequest(
    val name: String,
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
    @SerialName("node_id") val nodeId: String,
    @SerialName("network_id") val networkId: String? = null,
    @SerialName("server_type") val serverType: String,
    @SerialName("memory_mb") val memoryMb: Int,
    @SerialName("cpu_shares") val cpuShares: Int = 0,
)

@Serializable
data class PatchResourcesRequest(
    @SerialName("memory_mb") val memoryMb: Int,
    @SerialName("cpu_shares") val cpuShares: Int,
)

@Serializable
data class PatchExposureRequest(
    @SerialName("exposed_externally") val exposedExternally: Boolean,
    @SerialName("public_subdomain") val publicSubdomain: String? = null,
)

fun Route.serversRoutes() {
    authenticate("auth-jwt") {
        route("/api/v1/servers") {

            get {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)

                val visibility = resolveServerVisibility(userId)

                val servers = transaction {
                    val netIds = visibility.networkIds.toList()
                    val srvIds = visibility.serverIds.toList()

                    val rows = when {
                        visibility.isGlobal -> Servers.selectAll().toList()
                        netIds.isEmpty() && srvIds.isEmpty() -> return@transaction emptyList()
                        else -> Servers.selectAll().where {
                            val conds = buildList<Op<Boolean>> {
                                if (netIds.isNotEmpty()) add(Servers.networkId inList netIds)
                                if (srvIds.isNotEmpty()) add(Servers.id inList srvIds)
                            }
                            conds.reduce { a, b -> a or b }
                        }.toList()
                    }

                    val ids = rows.map { it[Servers.id] }
                    val migratingIds = if (ids.isEmpty()) emptySet() else {
                        ServerMigrations.selectAll()
                            .where {
                                (ServerMigrations.serverId inList ids) and
                                        (ServerMigrations.status inList listOf("PENDING", "RUNNING"))
                            }
                            .map { it[ServerMigrations.serverId] }
                            .toSet()
                    }

                    rows.map { row -> rowToServerResponse(row, row[Servers.id] in migratingIds) }
                }
                call.respond(servers)
            }

            post {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)
                if (!PermissionResolver.hasPermission(userId, "server.create")) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("message" to "Insufficient permissions"))
                    return@post
                }

                val req = call.receive<CreateServerRequest>()

                if (req.memoryMb <= 0) {
                    call.respond(HttpStatusCode.UnprocessableEntity, mapOf("message" to "memory_mb must be positive"))
                    return@post
                }
                if (req.cpuShares < 0) {
                    call.respond(HttpStatusCode.UnprocessableEntity, mapOf("message" to "cpu_shares must be non-negative"))
                    return@post
                }

                val nodeKotlinId = parseServerId(req.nodeId) ?: run {
                    call.respond(HttpStatusCode.UnprocessableEntity, mapOf("message" to "Invalid node_id"))
                    return@post
                }
                val networkKotlinId = req.networkId?.let {
                    parseServerId(it) ?: run {
                        call.respond(HttpStatusCode.UnprocessableEntity, mapOf("message" to "Invalid network_id"))
                        return@post
                    }
                }

                data class CreateResult(val status: String, val server: ServerResponse? = null)

                val result = transaction {
                    val node = Nodes.selectAll().where { Nodes.id eq nodeKotlinId }.firstOrNull()
                        ?: return@transaction CreateResult("node_not_found")

                    if (node[Nodes.status] != "ACTIVE")
                        return@transaction CreateResult("node_not_active")

                    if (networkKotlinId != null) {
                        val netExists = ServerNetworks.selectAll()
                            .where { ServerNetworks.id eq networkKotlinId }.firstOrNull() != null
                        if (!netExists) return@transaction CreateResult("network_not_found")
                    }

                    val nameTaken = Servers.selectAll().where { Servers.name eq req.name }.firstOrNull() != null
                    if (nameTaken) return@transaction CreateResult("name_taken")

                    val totalRam = node[Nodes.totalRamMb]
                    val totalCpu = node[Nodes.totalCpuShares]
                    val existing = Servers.selectAll().where { Servers.nodeId eq nodeKotlinId }.toList()
                    val usedRam = existing.sumOf { it[Servers.memoryMb] }
                    val usedCpu = existing.sumOf { it[Servers.cpuShares] }

                    if (usedRam + req.memoryMb > totalRam)
                        return@transaction CreateResult("insufficient_ram")
                    if (totalCpu > 0 && usedCpu + req.cpuShares > totalCpu)
                        return@transaction CreateResult("insufficient_cpu")

                    val portStart = node[Nodes.portRangeStart]
                    val portEnd = node[Nodes.portRangeEnd]
                    val usedPorts = PortRegistry.selectAll()
                        .where { (PortRegistry.nodeId eq nodeKotlinId) and (PortRegistry.protocol eq "TCP") }
                        .map { it[PortRegistry.port] }
                        .toSet()
                    val port = (portStart..portEnd).firstOrNull { it !in usedPorts }
                        ?: return@transaction CreateResult("no_ports")

                    val insertedId = Servers.insert {
                        it[name] = req.name
                        it[displayName] = req.displayName ?: req.name
                        it[description] = req.description
                        it[nodeId] = nodeKotlinId
                        it[networkId] = networkKotlinId
                        it[serverType] = req.serverType
                        it[gamePort] = port
                        it[memoryMb] = req.memoryMb
                        it[cpuShares] = req.cpuShares
                    }[Servers.id]

                    PortRegistry.insert {
                        it[PortRegistry.nodeId] = nodeKotlinId
                        it[PortRegistry.port] = port
                        it[PortRegistry.protocol] = "TCP"
                        it[PortRegistry.serverId] = insertedId
                    }

                    val row = Servers.selectAll().where { Servers.id eq insertedId }.first()
                    CreateResult("ok", rowToServerResponse(row, false))
                }

                when (result.status) {
                    "ok" -> call.respond(HttpStatusCode.Created, result.server!!)
                    "node_not_found" -> call.respond(HttpStatusCode.UnprocessableEntity, mapOf("message" to "Node not found"))
                    "node_not_active" -> call.respond(HttpStatusCode.UnprocessableEntity, mapOf("message" to "Node is not active"))
                    "network_not_found" -> call.respond(HttpStatusCode.UnprocessableEntity, mapOf("message" to "Network not found"))
                    "name_taken" -> call.respond(HttpStatusCode.Conflict, mapOf("message" to "Server name already taken"))
                    "insufficient_ram" -> call.respond(HttpStatusCode.Conflict, mapOf("message" to "Insufficient RAM capacity on node"))
                    "insufficient_cpu" -> call.respond(HttpStatusCode.Conflict, mapOf("message" to "Insufficient CPU capacity on node"))
                    "no_ports" -> call.respond(HttpStatusCode.Conflict, mapOf("message" to "No free ports available on node"))
                }
            }

            get("/{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)

                val id = parseServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid server ID"))
                    return@get
                }

                val (row, isMigrating) = transaction {
                    val r = Servers.selectAll().where { Servers.id eq id }.firstOrNull()
                        ?: return@transaction null
                    val migrating = ServerMigrations.selectAll()
                        .where {
                            (ServerMigrations.serverId eq id) and
                                    (ServerMigrations.status inList listOf("PENDING", "RUNNING"))
                        }
                        .firstOrNull() != null
                    r to migrating
                } ?: run {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "Server not found"))
                    return@get
                }

                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = row[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.view", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("message" to "Insufficient permissions"))
                    return@get
                }

                call.respond(rowToServerResponse(row, isMigrating))
            }

            patch("/{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)

                val id = parseServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid server ID"))
                    return@patch
                }

                val existing = transaction {
                    Servers.selectAll().where { Servers.id eq id }.firstOrNull()
                } ?: run {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "Server not found"))
                    return@patch
                }

                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = existing[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.configure", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("message" to "Insufficient permissions"))
                    return@patch
                }

                val body = call.receive<JsonObject>()
                val displayNameKey = "display_name" in body
                val descriptionKey = "description" in body
                val networkIdKey = "network_id" in body

                val newDisplayName = if (displayNameKey && body["display_name"] !is JsonNull)
                    body["display_name"]!!.jsonPrimitive.content else null
                val newDescription = if (descriptionKey && body["description"] !is JsonNull)
                    body["description"]!!.jsonPrimitive.content else null
                val newNetworkId: kotlin.uuid.Uuid? = if (networkIdKey && body["network_id"] !is JsonNull) {
                    val raw = body["network_id"]!!.jsonPrimitive.content
                    parseServerId(raw) ?: run {
                        call.respond(HttpStatusCode.UnprocessableEntity, mapOf("message" to "Invalid network_id"))
                        return@patch
                    }
                } else null

                val result = transaction {
                    if (networkIdKey && newNetworkId != null) {
                        val netExists = ServerNetworks.selectAll()
                            .where { ServerNetworks.id eq newNetworkId }.firstOrNull() != null
                        if (!netExists) return@transaction "network_not_found"
                    }

                    Servers.update({ Servers.id eq id }) {
                        if (displayNameKey && newDisplayName != null) it[displayName] = newDisplayName
                        if (descriptionKey) it[description] = newDescription
                        if (networkIdKey) it[networkId] = newNetworkId
                        it[updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                    }
                    "ok"
                }

                when (result) {
                    "ok" -> call.respond(HttpStatusCode.NoContent)
                    "network_not_found" -> call.respond(
                        HttpStatusCode.UnprocessableEntity, mapOf("message" to "Network not found")
                    )
                }
            }

            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)

                val id = parseServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid server ID"))
                    return@delete
                }

                val existing = transaction {
                    Servers.selectAll().where { Servers.id eq id }.firstOrNull()
                } ?: run {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "Server not found"))
                    return@delete
                }

                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = existing[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.delete", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("message" to "Insufficient permissions"))
                    return@delete
                }

                if (existing[Servers.status] != "STOPPED") {
                    call.respond(HttpStatusCode.Conflict, mapOf("message" to "Server must be STOPPED before deletion"))
                    return@delete
                }

                transaction {
                    PortRegistry.deleteWhere { PortRegistry.serverId eq id }
                    Servers.deleteWhere { Servers.id eq id }
                }
                call.respond(HttpStatusCode.NoContent)
            }

            patch("/{id}/resources") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)

                val id = parseServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid server ID"))
                    return@patch
                }

                val existing = transaction {
                    Servers.selectAll().where { Servers.id eq id }.firstOrNull()
                } ?: run {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "Server not found"))
                    return@patch
                }

                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = existing[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.resources", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("message" to "Insufficient permissions"))
                    return@patch
                }

                val req = call.receive<PatchResourcesRequest>()

                if (req.memoryMb <= 0) {
                    call.respond(HttpStatusCode.UnprocessableEntity, mapOf("message" to "memory_mb must be positive"))
                    return@patch
                }
                if (req.cpuShares < 0) {
                    call.respond(HttpStatusCode.UnprocessableEntity, mapOf("message" to "cpu_shares must be non-negative"))
                    return@patch
                }

                val nodeKotlinId = existing[Servers.nodeId]

                val result = transaction {
                    val node = Nodes.selectAll().where { Nodes.id eq nodeKotlinId }.firstOrNull()
                        ?: return@transaction "node_not_found"

                    val others = Servers.selectAll()
                        .where { (Servers.nodeId eq nodeKotlinId) and (Servers.id neq id) }
                        .toList()
                    val usedRam = others.sumOf { it[Servers.memoryMb] }
                    val usedCpu = others.sumOf { it[Servers.cpuShares] }
                    val totalRam = node[Nodes.totalRamMb]
                    val totalCpu = node[Nodes.totalCpuShares]

                    if (usedRam + req.memoryMb > totalRam) return@transaction "insufficient_ram"
                    if (totalCpu > 0 && usedCpu + req.cpuShares > totalCpu) return@transaction "insufficient_cpu"

                    Servers.update({ Servers.id eq id }) {
                        it[memoryMb] = req.memoryMb
                        it[cpuShares] = req.cpuShares
                        it[updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                    }
                    "ok"
                }

                when (result) {
                    "ok" -> call.respond(HttpStatusCode.NoContent)
                    "node_not_found" -> call.respond(HttpStatusCode.UnprocessableEntity, mapOf("message" to "Node not found"))
                    "insufficient_ram" -> call.respond(HttpStatusCode.Conflict, mapOf("message" to "Insufficient RAM capacity on node"))
                    "insufficient_cpu" -> call.respond(HttpStatusCode.Conflict, mapOf("message" to "Insufficient CPU capacity on node"))
                }
            }

            patch("/{id}/exposure") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)

                val id = parseServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid server ID"))
                    return@patch
                }

                val existing = transaction {
                    Servers.selectAll().where { Servers.id eq id }.firstOrNull()
                } ?: run {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "Server not found"))
                    return@patch
                }

                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = existing[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.configure", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("message" to "Insufficient permissions"))
                    return@patch
                }

                val req = call.receive<PatchExposureRequest>()

                val result = transaction {
                    if (req.publicSubdomain != null) {
                        val taken = Servers.selectAll()
                            .where { (Servers.publicSubdomain eq req.publicSubdomain) and (Servers.id neq id) }
                            .firstOrNull() != null
                        if (taken) return@transaction "subdomain_taken"
                    }

                    Servers.update({ Servers.id eq id }) {
                        it[exposedExternally] = req.exposedExternally
                        it[publicSubdomain] = req.publicSubdomain
                        it[updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                    }
                    "ok"
                }

                when (result) {
                    "ok" -> call.respond(HttpStatusCode.NoContent)
                    "subdomain_taken" -> call.respond(
                        HttpStatusCode.UnprocessableEntity, mapOf("message" to "Public subdomain already taken")
                    )
                }
            }
        }
    }
}

private data class ServerVisibility(
    val isGlobal: Boolean,
    val networkIds: Set<kotlin.uuid.Uuid>,
    val serverIds: Set<kotlin.uuid.Uuid>,
)

private fun resolveServerVisibility(userId: UUID): ServerVisibility = transaction {
    val kotlinUserId = userId.toKotlinUuid()

    val user = Users.selectAll().where { Users.id eq kotlinUserId }.firstOrNull()
    if (user == null || !user[Users.isActive]) return@transaction ServerVisibility(false, emptySet(), emptySet())

    val assignments = UserGroupAssignments.selectAll()
        .where { UserGroupAssignments.userId eq kotlinUserId }
        .toList()

    val groupIds = assignments.map { it[UserGroupAssignments.groupId] }.toSet()
    if (groupIds.isEmpty()) return@transaction ServerVisibility(false, emptySet(), emptySet())

    val viewGroups = GroupPermissions.selectAll()
        .where { GroupPermissions.groupId inList groupIds }
        .filter { permGrantsServerView(it[GroupPermissions.permission]) }
        .map { it[GroupPermissions.groupId] }
        .toSet()

    if (viewGroups.isEmpty()) return@transaction ServerVisibility(false, emptySet(), emptySet())

    var isGlobal = false
    val networkIds = mutableSetOf<kotlin.uuid.Uuid>()
    val serverIds = mutableSetOf<kotlin.uuid.Uuid>()

    for (a in assignments.filter { it[UserGroupAssignments.groupId] in viewGroups }) {
        when (a[UserGroupAssignments.scopeType]) {
            "GLOBAL" -> isGlobal = true
            "NETWORK" -> a[UserGroupAssignments.scopeId]?.let { networkIds += it }
            "SERVER" -> a[UserGroupAssignments.scopeId]?.let { serverIds += it }
        }
    }

    ServerVisibility(isGlobal, networkIds, serverIds)
}

private fun permGrantsServerView(granted: String) =
    granted == "*" || granted == "server.*" || granted == "server.view"

private fun rowToServerResponse(row: ResultRow, isMigrating: Boolean) = ServerResponse(
    id = row[Servers.id].toString(),
    name = row[Servers.name],
    displayName = row[Servers.displayName],
    description = row[Servers.description],
    serverType = row[Servers.serverType],
    status = row[Servers.status],
    nodeId = row[Servers.nodeId].toString(),
    networkId = row[Servers.networkId]?.toString(),
    gamePort = row[Servers.gamePort],
    memoryMb = row[Servers.memoryMb],
    cpuShares = row[Servers.cpuShares],
    exposedExternally = row[Servers.exposedExternally],
    publicSubdomain = row[Servers.publicSubdomain],
    isMigrating = isMigrating,
    configMode = row[Servers.configMode],
    createdAt = row[Servers.createdAt].toString(),
    updatedAt = row[Servers.updatedAt].toString(),
)

private fun parseServerId(raw: String?): kotlin.uuid.Uuid? =
    raw?.let { runCatching { UUID.fromString(it).toKotlinUuid() }.getOrNull() }
