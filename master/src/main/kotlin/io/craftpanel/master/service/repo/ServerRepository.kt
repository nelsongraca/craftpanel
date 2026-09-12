package io.craftpanel.master.service.repo

import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.util.parseUtcInstant
import kotlin.uuid.Uuid

data class ServerRow(
    val id: Uuid,
    val name: String,
    val displayName: String,
    val description: String?,
    val nodeId: Uuid,
    val networkId: Uuid?,
    val serverType: ServerType,
    val mcVersion: String,
    val status: String,
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
    val needsRecreate: Boolean,
    val disabled: Boolean = false,
    val expiresAt: String? = null,
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

fun ServerRow.isExpired(now: kotlin.time.Instant = kotlin.time.Clock.System.now()): Boolean {
    val raw = expiresAt ?: return false
    val expires = parseUtcInstant(raw) ?: return false
    return expires < now
}

fun ServerRow.isDisabled(now: kotlin.time.Instant = kotlin.time.Clock.System.now()): Boolean =
    disabled || isExpired(now)

fun ServerRow.disabledReason(): String = when {
    disabled -> "Server is disabled and can no longer be started"
    else -> "Server has expired and can no longer be started"
}

interface ServerRepository {
    fun findById(id: Uuid): ServerRow?
    fun findByName(name: String): ServerRow?
    fun findBySubdomain(subdomain: String): ServerRow?
    fun findByCustomHostname(hostname: String): ServerRow?
    fun findByDnsRecordName(hostname: String): ServerRow?
    fun listAll(): List<ServerRow>
    fun listByVisibility(networkIds: List<Uuid>, serverIds: List<Uuid>): List<ServerRow>
    fun listByNetworkId(networkId: Uuid): List<ServerRow>
    fun listByNodeId(nodeId: Uuid): List<ServerRow>
    fun listIds(ids: List<Uuid>): List<ServerRow>
    fun listWithBackupSchedule(): List<ServerRow>
    fun listExpiredRunning(now: kotlinx.datetime.LocalDateTime): List<ServerRow>
    fun countByNetworkId(networkId: Uuid): Int
    fun countByNodeId(nodeId: Uuid): Int
    fun findIdsNeedingRecreateByNode(nodeId: Uuid): List<Uuid>
    fun updateNeedsRecreate(id: Uuid, value: Boolean)
    fun updateForwardingSecret(id: Uuid, enc: String)
}
