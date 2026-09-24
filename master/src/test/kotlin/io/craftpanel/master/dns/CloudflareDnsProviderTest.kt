package io.craftpanel.master.dns

import io.craftpanel.master.service.BadGatewayException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private val VALID_ZONE_ID = "a".repeat(32)
private val RECORD_ID = "b".repeat(32)

private fun mockClient(handler: MockRequestHandler): HttpClient = HttpClient(MockEngine) {
    engine { addHandler(handler) }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

class CloudflareDnsProviderTest :
    FunSpec({

        test("createARecord returns record id on success") {
            val client = mockClient {
                respond(
                    """{"success":true,"result":{"id":"$RECORD_ID"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
            val provider = CloudflareDnsProvider("tok", client)
            provider.createARecord(VALID_ZONE_ID, "host.example.com", "1.2.3.4") shouldBe RECORD_ID
        }

        test("createARecord sends explicit type=A in request body") {
            var capturedBody = ""
            val client = mockClient { request ->
                capturedBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                respond(
                    """{"success":true,"result":{"id":"$RECORD_ID"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
            val provider = CloudflareDnsProvider("tok", client)
            provider.createARecord(VALID_ZONE_ID, "host.example.com", "1.2.3.4")
            capturedBody shouldContain "\"type\":\"A\""
        }

        test("updateARecord does not send name field in request body") {
            var capturedBody = ""
            val client = mockClient { request ->
                capturedBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                respond(
                    """{"success":true}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
            val provider = CloudflareDnsProvider("tok", client)
            provider.updateARecord(VALID_ZONE_ID, RECORD_ID, "5.6.7.8")
            capturedBody shouldNotContain "\"name\""
        }

        test("createARecord throws BadGatewayException on API error") {
            val client = mockClient {
                respond(
                    """{"success":false,"errors":[{"message":"Invalid zone"}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
            val provider = CloudflareDnsProvider("tok", client)
            shouldThrow<BadGatewayException> {
                runBlocking { provider.createARecord(VALID_ZONE_ID, "host.example.com", "1.2.3.4") }
            }
        }

        test("updateARecord completes without throw on success") {
            val client = mockClient {
                respond(
                    """{"success":true}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
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
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
            val provider = CloudflareDnsProvider("tok", client)
            shouldThrow<BadGatewayException> {
                runBlocking { provider.updateARecord(VALID_ZONE_ID, RECORD_ID, "5.6.7.8") }
            }
        }

        test("deleteARecord completes without throw on success") {
            val client = mockClient {
                respond(
                    """{"success":true}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
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
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
            val provider = CloudflareDnsProvider("tok", client)
            shouldThrow<BadGatewayException> {
                runBlocking { provider.deleteARecord(VALID_ZONE_ID, RECORD_ID) }
            }
        }

        test("invalid zone id throws IllegalArgumentException") {
            val client = mockClient { error("should not be called") }
            val provider = CloudflareDnsProvider("tok", client)
            shouldThrow<IllegalArgumentException> {
                runBlocking { provider.createARecord("short", "host", "1.2.3.4") }
            }
        }

        test("valid 32-char hex zone id passes validation") {
            val client = mockClient {
                respond(
                    """{"success":true,"result":{"id":"$RECORD_ID"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
            val provider = CloudflareDnsProvider("tok", client)
            provider.createARecord(VALID_ZONE_ID, "h", "1.2.3.4") shouldBe RECORD_ID
        }

        test("findARecord queries by type A and name and returns the first match") {
            val client = mockClient { request ->
                request.url.parameters["type"] shouldBe "A"
                request.url.parameters["name"] shouldBe "host.example.com"
                respond(
                    """{"success":true,"result":[{"id":"$RECORD_ID","name":"host.example.com"}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
            val found = runBlocking { CloudflareDnsProvider("tok", client).findARecord(VALID_ZONE_ID, "host.example.com") }
            found?.id shouldBe RECORD_ID
            found?.name shouldBe "host.example.com"
        }

        test("findARecord returns null when no record matches") {
            val client = mockClient {
                respond(
                    """{"success":true,"result":[]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
            runBlocking { CloudflareDnsProvider("tok", client).findARecord(VALID_ZONE_ID, "host.example.com") } shouldBe null
        }

        test("verifyZone throws BadGatewayException on API error") {
            val client = mockClient {
                respond(
                    """{"success":false,"errors":[{"code":9109,"message":"Cannot use the access token from location: 1.2.3.4"}]}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
            shouldThrow<BadGatewayException> {
                runBlocking { CloudflareDnsProvider("tok", client).verifyZone(VALID_ZONE_ID) }
            }
        }
    })
