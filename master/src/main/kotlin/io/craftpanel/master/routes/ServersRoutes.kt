package io.craftpanel.master.routes

import com.craftpanel.agent.v1.MasterMessage
import com.craftpanel.agent.v1.createContainerCommand
import com.craftpanel.agent.v1.masterMessage
import com.craftpanel.agent.v1.pullImageCommand
import com.craftpanel.agent.v1.removeContainerCommand
import com.craftpanel.agent.v1.restartContainerCommand
import com.craftpanel.agent.v1.startContainerCommand
import com.craftpanel.agent.v1.stopContainerCommand
import com.craftpanel.agent.v1.volumeMount
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.database.schema.GroupPermissions
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.PortRegistry
import io.craftpanel.master.database.schema.ServerEnvVars
import io.craftpanel.master.database.schema.ServerMigrations
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.database.schema.UserGroupAssignments
import io.craftpanel.master.database.schema.Users
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

@Serializable
data class UpgradeServerRequest(
    @SerialName("itzg_image_tag") val itzgImageTag: String,
)

fun Route.serversRoutes(sendToNode: (String, MasterMessage) -> Boolean) {
    authenticate("auth-jwt") {
        route("/api/v1/servers") {

            get("", {
                operationId = "listServers"
                summary = "List servers"
                response {
                    code(HttpStatusCode.OK) { body<List<ServerResponse>>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
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

            post("", {
                operationId = "createServer"
                summary = "Create server"
                request { body<CreateServerRequest>() }
                response {
                    code(HttpStatusCode.Created) { body<ServerResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
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

                val req = call.receive<CreateServerRequest>()

                if (req.memoryMb <= 0) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("memory_mb must be positive"))
                    return@post
                }
                if (req.cpuShares < 0) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("cpu_shares must be non-negative"))
                    return@post
                }

                val nodeKotlinId = parseServerId(req.nodeId) ?: run {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Invalid node_id"))
                    return@post
                }
                val networkKotlinId = req.networkId?.let {
                    parseServerId(it) ?: run {
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Invalid network_id"))
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
                    "node_not_found" -> call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Node not found"))
                    "node_not_active" -> call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Node is not active"))
                    "network_not_found" -> call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Network not found"))
                    "name_taken" -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Server name already taken"))
                    "insufficient_ram" -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Insufficient RAM capacity on node"))
                    "insufficient_cpu" -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Insufficient CPU capacity on node"))
                    "no_ports" -> call.respond(HttpStatusCode.Conflict, ErrorResponse("No free ports available on node"))
                }
            }

            get("/{id}", {
                operationId = "getServer"
                summary = "Get server"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.OK) { body<ServerResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)

                val id = parseServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
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
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@get
                }

                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = row[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.view", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@get
                }

                call.respond(rowToServerResponse(row, isMigrating))
            }

            patch("/{id}", {
                operationId = "updateServer"
                summary = "Update server"
                // Body uses tri-state: absent key = no change, null value = clear field, string = set field
                request { pathParameter<String>("id"); body<JsonObject>() }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)

                val id = parseServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@patch
                }

                val existing = transaction {
                    Servers.selectAll().where { Servers.id eq id }.firstOrNull()
                } ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@patch
                }

                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = existing[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.configure", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
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
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Invalid network_id"))
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
                        HttpStatusCode.UnprocessableEntity, ErrorResponse("Network not found")
                    )
                }
            }

            delete("/{id}", {
                operationId = "deleteServer"
                summary = "Delete server"
                request { pathParameter<String>("id") }
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

                val id = parseServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@delete
                }

                val existing = transaction {
                    Servers.selectAll().where { Servers.id eq id }.firstOrNull()
                } ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@delete
                }

                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = existing[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.delete", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@delete
                }

                if (existing[Servers.status] != "STOPPED") {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Server must be STOPPED before deletion"))
                    return@delete
                }

                transaction {
                    PortRegistry.deleteWhere { PortRegistry.serverId eq id }
                    Servers.deleteWhere { Servers.id eq id }
                }
                call.respond(HttpStatusCode.NoContent)
            }

            patch("/{id}/resources", {
                operationId = "updateServerResources"
                summary = "Update server resources"
                request { pathParameter<String>("id"); body<PatchResourcesRequest>() }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)

                val id = parseServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@patch
                }

                val existing = transaction {
                    Servers.selectAll().where { Servers.id eq id }.firstOrNull()
                } ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@patch
                }

                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = existing[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.resources", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@patch
                }

                val req = call.receive<PatchResourcesRequest>()

                if (req.memoryMb <= 0) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("memory_mb must be positive"))
                    return@patch
                }
                if (req.cpuShares < 0) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("cpu_shares must be non-negative"))
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
                    "node_not_found" -> call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Node not found"))
                    "insufficient_ram" -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Insufficient RAM capacity on node"))
                    "insufficient_cpu" -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Insufficient CPU capacity on node"))
                }
            }

            post("/{id}/start", {
                operationId = "startServer"
                summary = "Start server"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.Accepted) { body<MessageResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.BadGateway) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)

                val id = parseServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@post
                }

                val serverRow = transaction {
                    Servers.selectAll().where { Servers.id eq id }.firstOrNull()
                } ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@post
                }

                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = serverRow[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.start", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@post
                }

                val currentStatus = serverRow[Servers.status]
                if (currentStatus == "RUNNING" || currentStatus == "STARTING") {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Server is already running"))
                    return@post
                }

                val nodeKotlinId = serverRow[Servers.nodeId]
                val nodeRow = transaction {
                    Nodes.selectAll().where { Nodes.id eq nodeKotlinId }.firstOrNull()
                } ?: run {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Node not found"))
                    return@post
                }

                val dbEnvVars = transaction {
                    ServerEnvVars.selectAll().where { ServerEnvVars.serverId eq id }.toList()
                }

                val serverType = serverRow[Servers.serverType]
                val serverImage = deriveImage(serverType, serverRow[Servers.itzgImageTag])
                val dataVolumePath = "${nodeRow[Nodes.dataPath]}/servers/$id"
                val netName = serverRow[Servers.networkId]?.let { "craftpanel-net-$it" } ?: ""
                val systemVars = mapOf(
                    "EULA" to "TRUE",
                    "TYPE" to serverType,
                    "MEMORY" to "${serverRow[Servers.memoryMb]}M",
                )
                val userVars = dbEnvVars.associate { it[ServerEnvVars.key] to it[ServerEnvVars.value] }
                val allVars = systemVars + userVars

                val nodeId = nodeKotlinId.toString()
                val hasContainer = serverRow[Servers.containerId] != null

                if (!hasContainer) {
                    val createCmd = masterMessage {
                        createContainer = createContainerCommand {
                            serverId = id.toString()
                            containerName = "craftpanel-$id"
                            image = serverImage
                            ramMb = serverRow[Servers.memoryMb]
                            cpuShares = serverRow[Servers.cpuShares]
                            hostPort = serverRow[Servers.gamePort]
                            envVars.putAll(allVars)
                            this.mounts.add(volumeMount {
                                hostPath = dataVolumePath
                                containerPath = "/data"
                                readOnly = false
                            })
                            dockerNetwork = netName
                            restartPolicy = "unless-stopped"
                            stopCommand = serverRow[Servers.stopCommand]
                        }
                    }
                    if (!sendToNode(nodeId, createCmd)) {
                        call.respond(HttpStatusCode.BadGateway, ErrorResponse("Agent not connected"))
                        return@post
                    }
                }

                val startCmd = masterMessage {
                    startContainer = startContainerCommand {
                        serverId = id.toString()
                        containerName = "craftpanel-$id"
                    }
                }
                if (!sendToNode(nodeId, startCmd)) {
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse("Agent not connected"))
                    return@post
                }

                transaction {
                    Servers.update({ Servers.id eq id }) {
                        it[status] = "STARTING"
                        it[updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                    }
                }

                call.respond(HttpStatusCode.Accepted, MessageResponse("Server start initiated"))
            }

            post("/{id}/stop", {
                operationId = "stopServer"
                summary = "Stop server"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.Accepted) { body<MessageResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.BadGateway) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)

                val id = parseServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@post
                }

                val serverRow = transaction {
                    Servers.selectAll().where { Servers.id eq id }.firstOrNull()
                } ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@post
                }

                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = serverRow[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.stop", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@post
                }

                if (serverRow[Servers.status] == "STOPPED") {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Server is already stopped"))
                    return@post
                }

                val nodeId = serverRow[Servers.nodeId].toString()
                val stopCmd = masterMessage {
                    stopContainer = stopContainerCommand {
                        serverId = id.toString()
                        containerName = "craftpanel-$id"
                        timeoutSeconds = 30
                        stopCommand = serverRow[Servers.stopCommand]
                    }
                }
                if (!sendToNode(nodeId, stopCmd)) {
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse("Agent not connected"))
                    return@post
                }

                call.respond(HttpStatusCode.Accepted, MessageResponse("Server stop initiated"))
            }

            post("/{id}/restart", {
                operationId = "restartServer"
                summary = "Restart server"
                request { pathParameter<String>("id") }
                response {
                    code(HttpStatusCode.Accepted) { body<MessageResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.BadGateway) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)

                val id = parseServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@post
                }

                val serverRow = transaction {
                    Servers.selectAll().where { Servers.id eq id }.firstOrNull()
                } ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@post
                }

                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = serverRow[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.restart", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@post
                }

                val nodeId = serverRow[Servers.nodeId].toString()
                val restartCmd = masterMessage {
                    restartContainer = restartContainerCommand {
                        serverId = id.toString()
                        containerName = "craftpanel-$id"
                        timeoutSeconds = 30
                        stopCommand = serverRow[Servers.stopCommand]
                    }
                }
                if (!sendToNode(nodeId, restartCmd)) {
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse("Agent not connected"))
                    return@post
                }

                call.respond(HttpStatusCode.Accepted, MessageResponse("Server restart initiated"))
            }

            post("/{id}/upgrade", {
                operationId = "upgradeServer"
                summary = "Upgrade server image"
                request { pathParameter<String>("id"); body<UpgradeServerRequest>() }
                response {
                    code(HttpStatusCode.Accepted) { body<MessageResponse>() }
                    code(HttpStatusCode.Conflict) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.BadGateway) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)

                val id = parseServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@post
                }

                val serverRow = transaction {
                    Servers.selectAll().where { Servers.id eq id }.firstOrNull()
                } ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@post
                }

                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = serverRow[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.upgrade", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
                    return@post
                }

                if (serverRow[Servers.status] != "STOPPED") {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("Server must be STOPPED before upgrade"))
                    return@post
                }

                val req = call.receive<UpgradeServerRequest>()
                if (req.itzgImageTag.isBlank()) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("itzg_image_tag must not be blank"))
                    return@post
                }

                val nodeRow = transaction {
                    Nodes.selectAll().where { Nodes.id eq serverRow[Servers.nodeId] }.firstOrNull()
                } ?: run {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Node not found"))
                    return@post
                }

                val dbEnvVars = transaction {
                    ServerEnvVars.selectAll().where { ServerEnvVars.serverId eq id }.toList()
                }

                val nodeId = serverRow[Servers.nodeId].toString()
                val serverType = serverRow[Servers.serverType]
                val serverImage = deriveImage(serverType, req.itzgImageTag)

                // Remove old container if it exists
                if (serverRow[Servers.containerId] != null) {
                    val removeCmd = masterMessage {
                        removeContainer = removeContainerCommand {
                            serverId = id.toString()
                            containerName = "craftpanel-$id"
                            force = false
                        }
                    }
                    if (!sendToNode(nodeId, removeCmd)) {
                        call.respond(HttpStatusCode.BadGateway, ErrorResponse("Agent not connected"))
                        return@post
                    }
                }

                // Pull new image
                val pullCmd = masterMessage {
                    pullImage = pullImageCommand {
                        serverId = id.toString()
                        image = serverImage
                    }
                }
                if (!sendToNode(nodeId, pullCmd)) {
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse("Agent not connected"))
                    return@post
                }

                // Recreate container with new image
                val dataVolumePath = "${nodeRow[Nodes.dataPath]}/servers/$id"
                val netName = serverRow[Servers.networkId]?.let { "craftpanel-net-$it" } ?: ""
                val systemVars = mapOf(
                    "EULA" to "TRUE",
                    "TYPE" to serverType,
                    "MEMORY" to "${serverRow[Servers.memoryMb]}M",
                )
                val userVars = dbEnvVars.associate { it[ServerEnvVars.key] to it[ServerEnvVars.value] }
                val allVars = systemVars + userVars

                val createCmd = masterMessage {
                    createContainer = createContainerCommand {
                        serverId = id.toString()
                        containerName = "craftpanel-$id"
                        image = serverImage
                        ramMb = serverRow[Servers.memoryMb]
                        cpuShares = serverRow[Servers.cpuShares]
                        hostPort = serverRow[Servers.gamePort]
                        envVars.putAll(allVars)
                        this.mounts.add(volumeMount {
                            hostPath = dataVolumePath
                            containerPath = "/data"
                            readOnly = false
                        })
                        dockerNetwork = netName
                        restartPolicy = "unless-stopped"
                        stopCommand = serverRow[Servers.stopCommand]
                    }
                }
                if (!sendToNode(nodeId, createCmd)) {
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse("Agent not connected"))
                    return@post
                }

                // Update itzg_image_tag and clear containerId (will be set when agent reports back after create)
                transaction {
                    Servers.update({ Servers.id eq id }) {
                        it[Servers.itzgImageTag] = req.itzgImageTag
                        it[Servers.containerId] = null
                        it[updatedAt] = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                    }
                }

                call.respond(HttpStatusCode.Accepted, MessageResponse("Server upgrade initiated"))
            }

            patch("/{id}/exposure", {
                operationId = "updateServerExposure"
                summary = "Update server exposure"
                request { pathParameter<String>("id"); body<PatchExposureRequest>() }
                response {
                    code(HttpStatusCode.NoContent) { }
                    code(HttpStatusCode.UnprocessableEntity) { body<ErrorResponse>() }
                    code(HttpStatusCode.NotFound) { body<ErrorResponse>() }
                    code(HttpStatusCode.Forbidden) { body<ErrorResponse>() }
                    code(HttpStatusCode.Unauthorized) { body<ErrorResponse>() }
                }
            }) {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = UUID.fromString(principal.payload.subject)

                val id = parseServerId(call.parameters["id"]) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid server ID"))
                    return@patch
                }

                val existing = transaction {
                    Servers.selectAll().where { Servers.id eq id }.firstOrNull()
                } ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Server not found"))
                    return@patch
                }

                val serverIdJava = UUID.fromString(id.toString())
                val netIdJava = existing[Servers.networkId]?.let { UUID.fromString(it.toString()) }
                if (!PermissionResolver.hasPermission(userId, "server.configure", serverId = serverIdJava, networkId = netIdJava)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Insufficient permissions"))
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
                        HttpStatusCode.UnprocessableEntity, ErrorResponse("Public subdomain already taken")
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

private fun deriveImage(serverType: String, tag: String): String = when (serverType) {
    "BUNGEECORD", "VELOCITY", "WATERFALL" -> "itzg/mc-proxy:$tag"
    else -> "itzg/minecraft-server:$tag"
}
