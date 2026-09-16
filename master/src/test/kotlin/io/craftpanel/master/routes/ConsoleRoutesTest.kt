package io.craftpanel.master.routes

import io.craftpanel.master.*
import io.craftpanel.master.auth.PermissionResolver
import io.craftpanel.master.auth.WsAuthorization
import io.craftpanel.master.auth.WsTicketService
import io.craftpanel.master.grpc.BulkDataServiceImpl
import io.craftpanel.master.grpc.DataServiceProxy
import io.craftpanel.master.service.SystemService
import io.craftpanel.master.service.repo.impl.SettingsRepositoryImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.websocket.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.CloseReason
import kotlin.uuid.Uuid

class ConsoleRoutesTest :
    FunSpec({
        val repos = TestRepositories()
        val noopNodeRegistrar = createTestNodeRegistrar()
        val noopProxy = DataServiceProxy(createTestAgentDataOps(), BulkDataServiceImpl(noopNodeRegistrar), repos.serverRepository)
        val wsAuthorization = WsAuthorization(WsTicketService(), PermissionResolver)
        val systemService = SystemService(settingsRepository = SettingsRepositoryImpl())

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        fun Route.configureConsoleTest() {
            consoleRoutes(noopProxy, wsAuthorization, systemService)
        }

        // The console socket authorizes after the WS upgrade (a missing/invalid ticket closes with
        // 1008 rather than returning HTTP 401) — covered here through the WsAuthorization seam.
        test("console websocket closes with 1008 when the ticket is missing") {
            testApplication {
                testApp(extraPlugins = { install(WebSockets) }) { _ -> configureConsoleTest() }
                val client = jsonClient()

                client.webSocket("/api/ws/console/${Uuid.random()}") {
                    closeReason.await()?.code shouldBe CloseReason.Codes.VIOLATED_POLICY.code
                }
            }
        }

        test("console websocket closes with 1008 when the ticket is invalid") {
            testApplication {
                testApp(extraPlugins = { install(WebSockets) }) { _ -> configureConsoleTest() }
                val client = jsonClient()

                client.webSocket("/api/ws/console/${Uuid.random()}?ticket=bogus") {
                    closeReason.await()?.code shouldBe CloseReason.Codes.VIOLATED_POLICY.code
                }
            }
        }
    })
