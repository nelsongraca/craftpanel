package io.craftpanel.master.dns

/** An existing DNS record, identified by its provider-side [id]. */
data class DnsRecord(val id: String, val name: String?)

interface DnsProvider {

    val type: String

    suspend fun createARecord(zoneId: String, hostname: String, ip: String, ttl: Int = 60): String

    suspend fun updateARecord(zoneId: String, recordId: String, ip: String, ttl: Int = 60)

    suspend fun deleteARecord(zoneId: String, recordId: String)

    /**
     * Look up an existing A record by fully-qualified [hostname], or null when none exists. Used to
     * detect a record CraftPanel did not create before writing over a name.
     */
    suspend fun findARecord(zoneId: String, hostname: String): DnsRecord?

    /** Verify the provider credential can read DNS records in [zoneId]. Throws when it cannot. */
    suspend fun verifyZone(zoneId: String)
}
