package io.craftpanel.master.service.repo

import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

data class ServerRow(
    val id: Uuid,
    val name: String,
    val displayName: String,
    val description: String?,
    val nodeId: Uuid,
    val networkId: Uuid?,
    val serverType: String,
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

    fun create(
        name: String,
        displayName: String,
        description: String?,
        nodeId: Uuid,
        networkId: Uuid?,
        serverType: String,
        mcVersion: String,
        itzgImageTag: String,
        hostPort: Int,
        memoryMb: Int,
        cpuShares: Int,
        configMode: String,
        stopCommand: String
    ): ServerRow

    fun updateDetails(id: Uuid, displayName: String?, description: String?, networkId: Uuid?, mcVersion: String?, itzgImageTag: String?)
    fun clearNetworkId(id: Uuid)
    fun updateResources(id: Uuid, memoryMb: Int, cpuShares: Int, itzgImageTag: String?, needsRecreate: Boolean)
    fun updateStatus(id: Uuid, status: String, lastSeenAt: Instant?)
    fun updateExposure(id: Uuid, exposedExternally: Boolean?, publicSubdomain: String?, customHostname: String?, dnsRecordId: String?, dnsRecordName: String?, needsRecreate: Boolean?)
    fun updateNeedsRecreate(id: Uuid, needsRecreate: Boolean)
    fun updatePlayerInfo(id: Uuid, playerCount: Int?, playerNames: String?, lastUpdate: Instant?)
    fun updateBackupSchedule(id: Uuid, schedule: String?, maxCount: Int?)
    fun updateBackupScheduleLastFired(id: Uuid, lastFired: Instant?)
    fun updateConfigMode(id: Uuid, configMode: String)
    fun updateStopCommand(id: Uuid, stopCommand: String)
    fun delete(id: Uuid)
    fun nullifyNetworkId(networkId: Uuid)
}
