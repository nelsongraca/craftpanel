package io.craftpanel.master.dns

import io.craftpanel.master.service.BadGatewayException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val CF_API = "https://api.cloudflare.com/client/v4"
private val CF_ID_REGEX = Regex("^[a-f0-9]{32}$")

@Serializable
private data class DnsRecordBody(
    val type: String = "A",
    val name: String,
    val content: String,
    val ttl: Int,
)

@Serializable
private data class CfResponse(
    val success: Boolean,
    val errors: List<CfError> = emptyList(),
    val result: CfResult? = null,
)

@Serializable
private data class CfResult(val id: String)

@Serializable
private data class CfError(val message: String)

class CloudflareDnsProvider(private val apiToken: String) : DnsProvider {

    override val type = "cloudflare"

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private fun requireValidZoneId(zoneId: String) {
        require(CF_ID_REGEX.matches(zoneId)) { "Invalid Cloudflare zone ID format: $zoneId" }
    }

    override fun createARecord(zoneId: String, hostname: String, ip: String, ttl: Int): String {
        requireValidZoneId(zoneId)
        return runBlocking {
            val res: CfResponse = client.post("$CF_API/zones/$zoneId/dns_records") {
                bearerAuth(apiToken)
                contentType(ContentType.Application.Json)
                setBody(DnsRecordBody(name = hostname, content = ip, ttl = ttl))
            }
                .asCfResponse()
            if (!res.success) throw BadGatewayException("DNS error: ${res.errors.joinToString { it.message }}")
            res.result?.id ?: throw BadGatewayException("DNS error: no record id returned")
        }
    }

    override fun updateARecord(zoneId: String, recordId: String, ip: String, ttl: Int) {
        requireValidZoneId(zoneId)
        runBlocking {
            val res: CfResponse = client.patch("$CF_API/zones/$zoneId/dns_records/$recordId") {
                bearerAuth(apiToken)
                contentType(ContentType.Application.Json)
                setBody(DnsRecordBody(name = "", content = ip, ttl = ttl))
            }
                .asCfResponse()
            if (!res.success) throw BadGatewayException("DNS error: ${res.errors.joinToString { it.message }}")
        }
    }

    override fun deleteARecord(zoneId: String, recordId: String) {
        requireValidZoneId(zoneId)
        runBlocking {
            val res: CfResponse = client.delete("$CF_API/zones/$zoneId/dns_records/$recordId") {
                bearerAuth(apiToken)
            }
                .asCfResponse()
            if (!res.success) throw BadGatewayException("DNS error: ${res.errors.joinToString { it.message }}")
        }
    }

    private suspend fun HttpResponse.asCfResponse(): CfResponse = body()
}
