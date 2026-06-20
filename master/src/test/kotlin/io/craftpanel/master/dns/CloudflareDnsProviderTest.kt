package io.craftpanel.master.dns

import io.craftpanel.master.service.BadGatewayException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

private val VALID_ZONE_ID = "a".repeat(32)
private val RECORD_ID = "b".repeat(32)

private fun mockClient(handler: MockRequestHandler): HttpClient =
    HttpClient(MockEngine) {
        engine { addHandler(handler) }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

class CloudflareDnsProviderTest : FunSpec({

    test("createARecord returns record id on success") {
        val client = mockClient {
            respond(
                """{"success":true,"result":{"id":"$RECORD_ID"}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val provider = CloudflareDnsProvider("tok", client)
        provider.createARecord(VALID_ZONE_ID, "host.example.com", "1.2.3.4") shouldBe RECORD_ID
    }

    test("createARecord throws BadGatewayException on API error") {
        val client = mockClient {
            respond(
                """{"success":false,"errors":[{"message":"Invalid zone"}]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val provider = CloudflareDnsProvider("tok", client)
        shouldThrow<BadGatewayException> {
            provider.createARecord(VALID_ZONE_ID, "host.example.com", "1.2.3.4")
        }
    }

    test("updateARecord completes without throw on success") {
        val client = mockClient {
            respond(
                """{"success":true}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val provider = CloudflareDnsProvider("tok", client)
        provider.updateARecord(VALID_ZONE_ID, RECORD_ID, "5.6.7.8")
    }

    test("updateARecord throws BadGatewayException on failure") {
        val client = mockClient {
            respond(
                """{"success":false,"errors":[{"message":"Not found"}]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val provider = CloudflareDnsProvider("tok", client)
        shouldThrow<BadGatewayException> {
            provider.updateARecord(VALID_ZONE_ID, RECORD_ID, "5.6.7.8")
        }
    }

    test("deleteARecord completes without throw on success") {
        val client = mockClient {
            respond(
                """{"success":true}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val provider = CloudflareDnsProvider("tok", client)
        provider.deleteARecord(VALID_ZONE_ID, RECORD_ID)
    }

    test("deleteARecord throws BadGatewayException on failure") {
        val client = mockClient {
            respond(
                """{"success":false,"errors":[{"message":"Record not found"}]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val provider = CloudflareDnsProvider("tok", client)
        shouldThrow<BadGatewayException> {
            provider.deleteARecord(VALID_ZONE_ID, RECORD_ID)
        }
    }

    test("invalid zone id throws IllegalArgumentException") {
        val client = mockClient { error("should not be called") }
        val provider = CloudflareDnsProvider("tok", client)
        shouldThrow<IllegalArgumentException> {
            provider.createARecord("short", "host", "1.2.3.4")
        }
    }

    test("valid 32-char hex zone id passes validation") {
        val client = mockClient {
            respond(
                """{"success":true,"result":{"id":"$RECORD_ID"}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val provider = CloudflareDnsProvider("tok", client)
        provider.createARecord(VALID_ZONE_ID, "h", "1.2.3.4") shouldBe RECORD_ID
    }
})
