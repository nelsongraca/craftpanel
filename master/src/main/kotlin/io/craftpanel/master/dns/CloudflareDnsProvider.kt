package io.craftpanel.master.dns

import io.craftpanel.master.service.BadGatewayException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val CF_API = "https://api.cloudflare.com/client/v4"
private val CF_ID_REGEX = Regex("^[a-f0-9]{32}$")

@Serializable
private data class DnsRecordBody(val type: String, val name: String, val content: String, val ttl: Int)

@Serializable
private data class DnsRecordPatchBody(val content: String, val ttl: Int)

@Serializable
private data class CfResponse(val success: Boolean, val errors: List<CfError> = emptyList(), val result: CfResult? = null)

@Serializable
private data class CfListResponse(
    val success: Boolean,
    val errors: List<CfError> = emptyList(),
    val result: List<CfResult> = emptyList()
)

@Serializable
private data class CfResult(val id: String, val name: String? = null)

@Serializable
private data class CfError(val code: Int = 0, val message: String)

class CloudflareDnsProvider(
    private val apiToken: String,
    private val client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        // Bound every call so a stalled Cloudflare request fails the API request instead of hanging
        // the Netty event-loop thread it runs on.
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
    }
) : DnsProvider {

    override val type = "cloudflare"

    private fun requireValidZoneId(zoneId: String) {
        require(CF_ID_REGEX.matches(zoneId)) { "Invalid Cloudflare zone ID format: $zoneId" }
    }

    override suspend fun createARecord(zoneId: String, hostname: String, ip: String, ttl: Int): String {
        requireValidZoneId(zoneId)
        val res: CfResponse = client.post("$CF_API/zones/$zoneId/dns_records") {
            bearerAuth(apiToken)
            contentType(ContentType.Application.Json)
            setBody(DnsRecordBody(type = "A", name = hostname, content = ip, ttl = ttl))
        }.decodeCf()
        if (!res.success) throw BadGatewayException("DNS error: ${res.errors.joinToString { it.message }}")
        return res.result?.id ?: throw BadGatewayException("DNS error: no record id returned")
    }

    override suspend fun updateARecord(zoneId: String, recordId: String, ip: String, ttl: Int) {
        requireValidZoneId(zoneId)
        val res: CfResponse = client.patch("$CF_API/zones/$zoneId/dns_records/$recordId") {
            bearerAuth(apiToken)
            contentType(ContentType.Application.Json)
            setBody(DnsRecordPatchBody(content = ip, ttl = ttl))
        }.decodeCf()
        if (!res.success) throw BadGatewayException("DNS error: ${res.errors.joinToString { it.message }}")
    }

    override suspend fun deleteARecord(zoneId: String, recordId: String) {
        requireValidZoneId(zoneId)
        val res: CfResponse = client.delete("$CF_API/zones/$zoneId/dns_records/$recordId") {
            bearerAuth(apiToken)
        }.decodeCf()
        if (!res.success) throw BadGatewayException("DNS error: ${res.errors.joinToString { it.message }}")
    }

    override suspend fun findARecord(zoneId: String, hostname: String): DnsRecord? {
        requireValidZoneId(zoneId)
        val res: CfListResponse = client.get("$CF_API/zones/$zoneId/dns_records") {
            bearerAuth(apiToken)
            parameter("type", "A")
            parameter("name", hostname)
        }.decodeCf()
        if (!res.success) throw BadGatewayException("DNS error: ${res.errors.joinToString { it.message }}")
        return res.result.firstOrNull()?.let { DnsRecord(it.id, it.name) }
    }

    override suspend fun verifyZone(zoneId: String) {
        requireValidZoneId(zoneId)
        val res: CfListResponse = client.get("$CF_API/zones/$zoneId/dns_records") {
            bearerAuth(apiToken)
            parameter("per_page", "1")
        }.decodeCf()
        if (!res.success) throw BadGatewayException("DNS error: ${res.errors.joinToString { it.message }}")
    }

    /**
     * Decode a Cloudflare response, converting an unreadable body (e.g. an HTML gateway error) into a
     * [BadGatewayException] instead of leaking a serialization exception.
     */
    private suspend inline fun <reified T> HttpResponse.decodeCf(): T = try {
        body<T>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BadGatewayException("DNS error: unreadable response (${e.message})")
    }
}
