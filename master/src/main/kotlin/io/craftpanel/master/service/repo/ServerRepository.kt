package io.craftpanel.master.service.repo

import io.craftpanel.master.domain.ServerType
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
    fun countByNetworkId(networkId: Uuid): Int
    fun countByNodeId(nodeId: Uuid): Int
    fun findIdsNeedingRecreateByNode(nodeId: Uuid): List<Uuid>
    fun updateNeedsRecreate(id: Uuid, value: Boolean)
    fun updateForwardingSecret(id: Uuid, enc: String)
}
