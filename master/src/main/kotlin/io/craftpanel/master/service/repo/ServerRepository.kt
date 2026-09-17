package io.craftpanel.master.service.repo

import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.util.parseUtcInstant
import kotlin.uuid.Uuid

data class ServerView(
    val id: Uuid,
    val name: String,
    val displayName: String,
    val description: String?,
    val nodeId: Uuid,
    val networkId: Uuid?,
    val serverType: ServerType,
    val mcVersion: String,
    val status: String,
    val desiredStatus: String? = null,
    val hostPort: Int,
    val memoryMb: Int,
    val cpuShares: Int,
    val exposedExternally: Boolean,
    val publicSubdomain: String?,
    val dnsRecordId: String?,
    val dnsRecordName: String?,
    val customHostname: String?,
    val configMode: String,
    val stopCommand: String,
    val itzgImageTag: String,
    val disabled: Boolean = false,
    val expiresAt: String? = null,
    val customServerJar: String? = null,
    val containerListenPort: Int? = null,
    val containerProtocol: String = "TCP",
    val disableHealthcheck: Boolean = false,
    val forceRedownload: Boolean = false,
    // Admin override for the data directory name; null = use the server id.
    val dataDirName: String? = null,
    val proxyMotd: String? = null,
    val proxyMaxPlayers: Int? = null,
    val proxyForwardingMode: String? = null,
    val forwardingSecretEnc: String? = null,
    val backupSchedule: String?,
    val backupMaxCount: Int,
    val backupScheduleLastFired: String?,
    val lastPlayerCount: Int?,
    val lastPlayerNames: String?,
    val lastPlayerUpdate: String?,
    val lastSeenAt: String?,
    val createdAt: String,
    val updatedAt: String
)

/**
 * Custom hostnames are persisted comma-separated (mc-router accepts a comma-separated host list).
 * Parse a stored value into a normalized list: trimmed, blanks dropped, duplicates removed,
 * order preserved.
 */
fun parseCustomHostnames(raw: String?): List<String> =
    raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct() ?: emptyList()

fun ServerView.customHostnames(): List<String> = parseCustomHostnames(customHostname)

fun ServerView.isExpired(now: kotlin.time.Instant = kotlin.time.Clock.System.now()): Boolean {
    val raw = expiresAt ?: return false
    val expires = parseUtcInstant(raw) ?: return false
    return expires < now
}

fun ServerView.isDisabled(now: kotlin.time.Instant = kotlin.time.Clock.System.now()): Boolean = disabled || isExpired(now)

fun ServerView.disabledReason(): String = when {
    disabled -> "Server is disabled and can no longer be started"
    else -> "Server has expired and can no longer be started"
}

interface ServerRepository {
    fun findById(id: Uuid): ServerView?
    fun findByName(name: String): ServerView?
    fun findBySubdomain(subdomain: String): ServerView?
    fun findByCustomHostname(hostname: String): ServerView?
    fun findByDnsRecordName(hostname: String): ServerView?
    fun listAll(): List<ServerView>
    fun listByVisibility(networkIds: List<Uuid>, serverIds: List<Uuid>): List<ServerView>
    fun listByNetworkId(networkId: Uuid): List<ServerView>
    fun listByNodeId(nodeId: Uuid): List<ServerView>
    fun listIds(ids: List<Uuid>): List<ServerView>
    fun listWithBackupSchedule(): List<ServerView>
    fun listExpiredRunning(now: kotlinx.datetime.LocalDateTime): List<ServerView>
    fun countByNetworkId(networkId: Uuid): Int
    fun countByNodeId(nodeId: Uuid): Int
}
