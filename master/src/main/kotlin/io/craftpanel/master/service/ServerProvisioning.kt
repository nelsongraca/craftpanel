package io.craftpanel.master.service

import io.craftpanel.common.ContainerNames
import io.craftpanel.common.ServerNames
import io.craftpanel.master.database.entity.EnvVar
import io.craftpanel.master.database.entity.Mod
import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.PortRegistry
import io.craftpanel.master.database.schema.ServerNetworks
import io.craftpanel.master.database.schema.Servers
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.EnvVarsRepository
import io.craftpanel.master.service.repo.ModRepository
import io.craftpanel.master.service.repo.NetworkRepository
import io.craftpanel.master.service.repo.NodeRepository
import io.craftpanel.master.service.repo.ServerExtraPortRepository
import io.craftpanel.master.service.repo.ServerRepository
import io.craftpanel.master.service.repo.ServerView
import io.craftpanel.master.util.parseUtcInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.uuid.Uuid

/**
 * A server to materialise, expressed independently of any wire format (REST request, export file,
 * clone source). `null` on an override means "derive/seed the default"; a non-null value is applied
 * verbatim, so callers never create-then-overwrite.
 */
data class ServerProvisionSpec(
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val nodeId: String,
    val networkId: String? = null,
    val serverType: String,
    val mcVersion: String,
    val itzgImageTag: String,
    val memoryMb: Int,
    val cpuLimitMillicores: Int,
    val expiresAt: String? = null,
    val customServerJar: String? = null,
    val containerListenPort: Int? = null,
    val containerProtocol: String? = null,
    val disableHealthcheck: Boolean? = null,
    val forceRedownload: Boolean? = null,
    // Configuration overrides — null derives the default.
    val configMode: String? = null,
    val stopCommand: String? = null,
    val proxyMotd: String? = null,
    val proxyMaxPlayers: Int? = null,
    val proxyForwardingMode: String? = null,
    val forwardingSecretEnc: String? = null,
    val backupSchedule: String? = null,
    val backupMaxCount: Int? = null,
    val exposedExternally: Boolean? = null,
    val customHostname: String? = null,
    // Env vars: null seeds the type's defaults, non-null is used verbatim (even empty).
    val envVars: Map<String, String>? = null,
    val mods: List<ProvisionMod> = emptyList(),
    val extraPorts: List<ProvisionExtraPort> = emptyList()
)

data class ProvisionMod(
    val modrinthProjectId: String,
    val displayName: String,
    val pinStrategy: String,
    val pinnedVersionId: String? = null,
    val installedVersionId: String? = null,
    val enabled: Boolean = true
)

data class ProvisionExtraPort(val name: String, val containerPort: Int, val protocol: String)

/**
 * The one module that turns a [ServerProvisionSpec] into a persisted server. Owns validation, name
 * uniqueness, node capacity, port allocation (with retry on port collision), default env seeding,
 * and the optional mods/extra-ports lists — all in a single transaction, so a server is either fully
 * materialised or not created at all.
 *
 * `provision` never swallows failures. Callers resolve anything they cannot express in the spec
 * (e.g. cross-server proxy backends) before calling it.
 */
class ServerProvisioning(
    private val serverRepository: ServerRepository,
    private val nodeRepository: NodeRepository,
    private val networkRepository: NetworkRepository,
    private val settingsProvider: SettingsProvider,
    private val portAllocator: PortAllocator,
    private val extraPortRepository: ServerExtraPortRepository,
    private val envVarsRepository: EnvVarsRepository,
    private val modRepository: ModRepository,
    private val networkService: NetworkService,
    private val containerNamePrefix: String = ContainerNames.DEFAULT_PREFIX
) {

    private val log = LoggerFactory.getLogger(ServerProvisioning::class.java)
    private val capacityChecker = ResourceCapacityChecker(serverRepository)

    fun provision(spec: ServerProvisionSpec): ServerView {
        if (!ServerNames.isValid(spec.name)) {
            throw UnprocessableException(
                "Invalid server name: must match [a-z0-9][a-z0-9-]* and be at most ${ServerNames.MAX_LENGTH} characters"
            )
        }
        if (ServerNames.collidesWithPrefix(spec.name, containerNamePrefix)) {
            throw UnprocessableException("Server name must not start with the reserved prefix '${containerNamePrefix.trim().trimEnd('-')}-'")
        }
        if (spec.memoryMb <= 0) throw UnprocessableException("memory_mb must be positive")
        if (spec.cpuLimitMillicores < 0) throw UnprocessableException("cpu_limit_millicores must be non-negative")
        val expiryLocal = parseExpiresAt(spec.expiresAt)

        val st = runCatching { ServerType.valueOf(spec.serverType) }.getOrNull()
            ?: throw UnprocessableException("Invalid server_type: ${spec.serverType}")
        val proto = runCatching { validateContainerProtocol(spec.containerProtocol ?: "TCP") }.getOrNull()
            ?: throw UnprocessableException("Invalid container_protocol: ${spec.containerProtocol ?: "TCP"}")
        if (st.isCustom && spec.customServerJar.isNullOrBlank()) {
            throw UnprocessableException("custom_server_jar is required for CUSTOM server type")
        }
        if (st.isPicolimbo && !spec.customServerJar.isNullOrBlank()) {
            throw UnprocessableException("custom_server_jar is not supported for PICOLIMBO server type")
        }
        if (spec.containerListenPort != null && (spec.containerListenPort <= 0 || spec.containerListenPort > 65535)) {
            throw UnprocessableException("container_listen_port must be between 1 and 65535")
        }
        val nodeKotlinId = parseUuid(spec.nodeId) ?: throw UnprocessableException("Invalid node_id")
        val networkKotlinId = spec.networkId?.let { parseUuid(it) ?: throw UnprocessableException("Invalid network_id") }

        if (networkKotlinId != null) {
            networkService.requireSingleNodeForNetwork(networkKotlinId, nodeKotlinId)
        }

        return run {
            var lastEx: java.sql.SQLException? = null
            repeat(3) {
                try {
                    return@run attemptCreate(spec, st, proto, expiryLocal, nodeKotlinId, networkKotlinId)
                } catch (ex: Exception) {
                    val cause = generateSequence(ex as Throwable) { it.cause }
                        .filterIsInstance<java.sql.SQLException>()
                        .firstOrNull()
                    if (cause != null && cause.sqlState?.startsWith("23") == true) {
                        lastEx = cause
                    } else {
                        throw ex
                    }
                }
            }
            throw lastEx ?: RuntimeException("port allocation failed after retries")
        }
    }

    /**
     * Provision a new server copied from [sourceId]'s runtime definition: env vars, mods, extra
     * ports, proxy fields, config/stop command and container settings. Identity/exposure (hostname,
     * DNS, exposure), per-instance state (expiry, disabled) and cross-server wiring (proxy backends)
     * are deliberately not copied.
     */
    fun clone(sourceId: Uuid, name: String, displayName: String?, description: String?): ServerView {
        val source = serverRepository.findById(sourceId)
            ?: throw NotFoundException("Source server not found")

        val spec = ServerProvisionSpec(
            name = name,
            displayName = displayName ?: source.displayName,
            description = description ?: source.description,
            nodeId = source.nodeId.toString(),
            networkId = source.networkId?.toString(),
            serverType = source.serverType.toDb(),
            mcVersion = source.mcVersion,
            itzgImageTag = source.itzgImageTag,
            memoryMb = source.memoryMb,
            cpuLimitMillicores = source.cpuLimitMillicores,
            customServerJar = source.customServerJar,
            containerListenPort = source.containerListenPort,
            containerProtocol = source.containerProtocol,
            disableHealthcheck = source.disableHealthcheck,
            forceRedownload = source.forceRedownload,
            configMode = source.configMode,
            stopCommand = source.stopCommand,
            proxyMotd = source.proxyMotd,
            proxyMaxPlayers = source.proxyMaxPlayers,
            proxyForwardingMode = source.proxyForwardingMode,
            forwardingSecretEnc = source.forwardingSecretEnc,
            backupSchedule = source.backupSchedule,
            backupMaxCount = source.backupMaxCount,
            envVars = envVarsRepository.getEnvVars(sourceId).associate { it.key to it.value },
            mods = modRepository.listMods(sourceId).map {
                ProvisionMod(
                    modrinthProjectId = it.modrinthProjectId,
                    displayName = it.displayName,
                    pinStrategy = it.pinStrategy,
                    pinnedVersionId = it.pinnedVersionId,
                    installedVersionId = it.installedVersionId
                )
            },
            extraPorts = extraPortRepository.findByServerId(sourceId).map {
                ProvisionExtraPort(name = it.name, containerPort = it.containerPort, protocol = it.protocol)
            }
        )
        return provision(spec)
    }

    private fun attemptCreate(spec: ServerProvisionSpec, st: ServerType, proto: String, expiryLocal: kotlinx.datetime.LocalDateTime?, nodeKotlinId: Uuid, networkKotlinId: Uuid?): ServerView {
        val node = nodeRepository.findById(nodeKotlinId) ?: throw UnprocessableException("Node not found")
        if (node.status != "ACTIVE") throw UnprocessableException("Node is not active")
        if (networkKotlinId != null && networkRepository.findById(networkKotlinId) == null) {
            throw UnprocessableException("Network not found")
        }
        if (serverRepository.findByName(spec.name) != null) throw ConflictException("Server name already taken")

        when (capacityChecker.check(node, excludeServerId = null, memoryMb = spec.memoryMb, cpuLimitMillicores = spec.cpuLimitMillicores)) {
            CapacityResult.InsufficientRam -> throw ConflictException("Insufficient RAM capacity on node")
            CapacityResult.InsufficientCpu -> throw ConflictException("Insufficient CPU capacity on node")
            CapacityResult.Ok -> {}
        }

        val port = portAllocator.allocate(nodeKotlinId)

        val platformName = settingsProvider.current().appName
        val serverTypeDisplay = spec.serverType.lowercase()
            .replaceFirstChar { it.uppercase() }

        return transaction {
            val entity = Server.new {
                this.name = spec.name
                this.displayName = spec.displayName ?: spec.name
                this.description = spec.description
                this.nodeId = EntityID(nodeKotlinId, Nodes)
                this.networkId = networkKotlinId?.let { EntityID(it, ServerNetworks) }
                this.serverType = st.toDb()
                this.mcVersion = spec.mcVersion
                this.itzgImageTag = spec.itzgImageTag
                this.hostPort = port
                this.memoryMb = spec.memoryMb
                this.cpuLimitMillicores = spec.cpuLimitMillicores
                this.expiresAt = expiryLocal
                this.configMode = spec.configMode ?: if (st.isCustom || st.isPicolimbo) "MANUAL" else "MANAGED"
                this.stopCommand = spec.stopCommand ?: if (st.isProxy) "end" else "stop"
                this.customServerJar = spec.customServerJar
                this.containerListenPort = spec.containerListenPort
                this.containerProtocol = proto
                this.disableHealthcheck = spec.disableHealthcheck ?: false
                this.forceRedownload = spec.forceRedownload ?: false
            }

            PortRegistry.insert {
                it[PortRegistry.nodeId] = EntityID(nodeKotlinId, Nodes)
                it[PortRegistry.port] = port
                it[PortRegistry.protocol] = proto
                it[PortRegistry.serverId] = EntityID(entity.id.value, Servers)
            }

            spec.exposedExternally?.let { entity.exposedExternally = it }
            spec.customHostname?.let { entity.customHostname = it }
            spec.backupSchedule?.let { entity.backupSchedule = it }
            spec.backupMaxCount?.let { entity.backupMaxCount = it }
            if (st.isProxy) {
                entity.proxyMotd = spec.proxyMotd ?: "$serverTypeDisplay powered by $platformName"
            } else {
                entity.proxyMotd = spec.proxyMotd
            }
            entity.proxyMaxPlayers = spec.proxyMaxPlayers
            entity.proxyForwardingMode = spec.proxyForwardingMode
            entity.forwardingSecretEnc = spec.forwardingSecretEnc

            val envVars = spec.envVars
                ?: if (!st.isProxy && !st.isCustom && !st.isPicolimbo) {
                    buildDefaultEnvVars(spec.mcVersion, serverTypeDisplay, platformName)
                } else {
                    emptyMap()
                }
            envVars.forEach { (key, value) ->
                EnvVar.new {
                    this.serverId = EntityID(entity.id.value, Servers)
                    this.key = key
                    this.value = value
                }
            }

            spec.mods.forEach { mod ->
                Mod.new {
                    this.serverId = EntityID(entity.id.value, Servers)
                    this.modrinthProjectId = mod.modrinthProjectId
                    this.displayName = mod.displayName
                    this.pinStrategy = mod.pinStrategy
                    this.pinnedVersionId = mod.pinnedVersionId
                    this.installedVersionId = mod.installedVersionId
                    this.enabled = mod.enabled
                }
            }

            spec.extraPorts.forEach { extra ->
                extraPortRepository.createExtraPort(
                    serverId = entity.id.value,
                    nodeId = nodeKotlinId,
                    name = extra.name,
                    containerPort = extra.containerPort,
                    hostPort = null,
                    protocol = extra.protocol
                )
            }

            entity.toServerView()
        }
    }
}

internal fun parseExpiresAt(raw: String?): kotlinx.datetime.LocalDateTime? = raw?.let {
    val parsed = parseUtcInstant(it)
        ?: throw UnprocessableException("Invalid expires_at")
    parsed.toLocalDateTime(TimeZone.UTC)
}

internal fun parseUuid(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()

internal fun validateContainerProtocol(raw: String): String {
    val normalized = raw.uppercase()
    if (normalized != "TCP" && normalized != "UDP") {
        throw UnprocessableException("Invalid container_protocol: $raw")
    }
    return normalized
}

internal fun buildDefaultEnvVars(mcVersion: String, serverTypeDisplay: String, platformName: String) = mapOf(
    "MOTD" to "$mcVersion $serverTypeDisplay powered by $platformName",
    "DIFFICULTY" to "easy",
    "MODE" to "survival",
    "HARDCORE" to "false",
    "PVP" to "true",
    "ALLOW_NETHER" to "true",
    "FORCE_GAMEMODE" to "false",
    "SPAWN_ANIMALS" to "true",
    "SPAWN_MONSTERS" to "true",
    "SPAWN_NPCS" to "true",
    "SPAWN_PROTECTION" to "16",
    "ALLOW_FLIGHT" to "false",
    "LEVEL" to "world",
    "LEVEL_TYPE" to "DEFAULT",
    "GENERATE_STRUCTURES" to "true",
    "MAX_WORLD_SIZE" to "29999984",
    "MAX_PLAYERS" to "20",
    "ONLINE_MODE" to "true",
    "ENABLE_WHITELIST" to "false",
    "EXISTING_WHITELIST_FILE" to "SYNCHRONIZE",
    "EXISTING_OPS_FILE" to "SYNCHRONIZE",
    "PLAYER_IDLE_TIMEOUT" to "0",
    "ENFORCE_SECURE_PROFILE" to "true",
    "PREVENT_PROXY_CONNECTIONS" to "false",
    "VIEW_DISTANCE" to "10",
    "SIMULATION_DISTANCE" to "10",
    "MAX_TICK_TIME" to "60000",
    "NETWORK_COMPRESSION_THRESHOLD" to "256",
    "SYNC_CHUNK_WRITES" to "true",
    "ENABLE_COMMAND_BLOCK" to "false",
    "OP_PERMISSION_LEVEL" to "4",
    "FUNCTION_PERMISSION_LEVEL" to "2",
    "BROADCAST_CONSOLE_TO_OPS" to "true",
    "TZ" to "UTC",
    "USE_AIKAR_FLAGS" to "true"
)
