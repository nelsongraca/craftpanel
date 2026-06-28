package io.craftpanel.master.service.repo

import kotlin.uuid.Uuid

data class NetworkRow(
    val id: Uuid,
    val name: String,
    val proxyPort: Int?,
    val description: String?,
    val cfZoneId: String?,
    val cfDomainSuffix: String?,
    val dnsProviderType: String?,
    val createdAt: String,
)

interface NetworkRepository {

    fun findById(id: Uuid): NetworkRow?
    fun findByName(name: String): NetworkRow?
    fun listAll(): List<NetworkRow>
    fun listByIds(ids: List<Uuid>): List<NetworkRow>
    fun create(
        name: String,
        proxyPort: Int?,
        description: String?,
        cfDomainSuffix: String?,
        cfZoneId: String?,
        dnsProviderType: String?,
    ): NetworkRow

    fun update(id: Uuid, name: String?, description: String?, cfDomainSuffix: String?, cfZoneId: String?, dnsProviderType: String?)
    fun delete(id: Uuid)
}
