package io.craftpanel.master.dns

interface DnsProvider {

    val type: String

    fun createARecord(zoneId: String, hostname: String, ip: String, ttl: Int = 60): String

    fun updateARecord(zoneId: String, recordId: String, ip: String, ttl: Int = 60)

    fun deleteARecord(zoneId: String, recordId: String)
}
