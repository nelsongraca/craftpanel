package io.craftpanel.master.service

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.service.repo.ProxyBackendInput
import io.craftpanel.master.service.repo.ServerRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class ProxyConfigPatchServiceTest :
    FunSpec({
        val repos = TestRepositories()
        val serverRepository: ServerRepository = repos.serverRepository
        val service = ProxyConfigPatchService(repos.proxyBackendRepository, serverRepository)

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        fun createNode(): Uuid = transaction {
            Nodes.insert {
                it[Nodes.hostname] = "node-1"
                it[Nodes.displayName] = "node-1"
                it[Nodes.publicIp] = "1.2.3.4"
                it[Nodes.privateIp] = "10.0.0.1"
                it[Nodes.tokenHash] = "a".repeat(64)
                it[Nodes.status] = "ACTIVE"
                it[Nodes.totalRamMb] = 8192
                it[Nodes.totalCpuShares] = 0
                it[Nodes.portRangeStart] = 25565
                it[Nodes.portRangeEnd] = 25600
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        fun createServer(nodeId: Uuid, name: String, type: String): Uuid = serverRepository.create(
            name = name,
            displayName = name,
            description = null,
            nodeId = nodeId,
            networkId = null,
            serverType = type,
            mcVersion = "1.21.4",
            itzgImageTag = "latest",
            hostPort = 25565,
            memoryMb = 1024,
            cpuShares = 0,
            configMode = "MANAGED",
            stopCommand = "stop"
        ).id

        test("generates Velocity patch with all settings and backends") {
            val nodeId = createNode()
            val proxyId = createServer(nodeId, "proxy-${Uuid.random()}", "VELOCITY")
            serverRepository.updateProxySettings(proxyId, "Welcome", 20, "LEGACY")

            val alphaId = createServer(nodeId, "alpha-${Uuid.random()}", "VANILLA")
            val betaId = createServer(nodeId, "beta-${Uuid.random()}", "PAPER")
            repos.proxyBackendRepository.replaceProxyBackends(
                proxyId,
                listOf(
                    ProxyBackendInput(alphaId, "alpha", 0),
                    ProxyBackendInput(betaId, "beta", 1)
                )
            )

            val patch = service.generatePatch(proxyId)
            val json = Json.parseToJsonElement(patch).jsonArray

            json.size shouldBe 4

            val serversOp = json[0].jsonObject
            serversOp["op"] shouldBe JsonPrimitive("\$set")
            serversOp["path"] shouldBe JsonPrimitive("$.servers")
            val servers = serversOp["value"]!!.jsonObject
            servers["alpha"] shouldBe JsonPrimitive("craftpanel-$alphaId:25565")
            servers["beta"] shouldBe JsonPrimitive("craftpanel-$betaId:25565")
            servers["try"] shouldBe JsonArray(listOf(JsonPrimitive("alpha"), JsonPrimitive("beta")))

            val motdOp = json[1].jsonObject
            motdOp["op"] shouldBe JsonPrimitive("\$set")
            motdOp["path"] shouldBe JsonPrimitive("$.motd")
            motdOp["value"] shouldBe JsonPrimitive("Welcome")

            val maxPlayersOp = json[2].jsonObject
            maxPlayersOp["op"] shouldBe JsonPrimitive("\$set")
            maxPlayersOp["path"] shouldBe JsonPrimitive("\$['show-max-players']")
            maxPlayersOp["value"] shouldBe JsonPrimitive(20)
            maxPlayersOp["value-type"] shouldBe JsonPrimitive("int")

            val forwardingOp = json[3].jsonObject
            forwardingOp["op"] shouldBe JsonPrimitive("\$set")
            forwardingOp["path"] shouldBe JsonPrimitive("\$['player-info-forwarding-mode']")
            forwardingOp["value"] shouldBe JsonPrimitive("legacy")
        }

        test("generates BungeeCord patch with all settings and backends") {
            val nodeId = createNode()
            val proxyId = createServer(nodeId, "proxy-${Uuid.random()}", "BUNGEECORD")
            serverRepository.updateProxySettings(proxyId, "Welcome", 20, "LEGACY")

            val alphaId = createServer(nodeId, "alpha-${Uuid.random()}", "VANILLA")
            val betaId = createServer(nodeId, "beta-${Uuid.random()}", "PAPER")
            repos.proxyBackendRepository.replaceProxyBackends(
                proxyId,
                listOf(
                    ProxyBackendInput(alphaId, "alpha", 0),
                    ProxyBackendInput(betaId, "beta", 1)
                )
            )

            val patch = service.generatePatch(proxyId)
            val json = Json.parseToJsonElement(patch).jsonArray

            json.size shouldBe 5

            val serversOp = json[0].jsonObject
            serversOp["op"] shouldBe JsonPrimitive("\$set")
            serversOp["path"] shouldBe JsonPrimitive("$.servers")
            val servers = serversOp["value"]!!.jsonObject
            servers["alpha"] shouldBe JsonObject(
                mapOf(
                    "address" to JsonPrimitive("craftpanel-$alphaId:25565"),
                    "restricted" to JsonPrimitive(false)
                )
            )
            servers["beta"] shouldBe JsonObject(
                mapOf(
                    "address" to JsonPrimitive("craftpanel-$betaId:25565"),
                    "restricted" to JsonPrimitive(false)
                )
            )

            val prioritiesOp = json[1].jsonObject
            prioritiesOp["op"] shouldBe JsonPrimitive("\$set")
            prioritiesOp["path"] shouldBe JsonPrimitive("$.listeners[0].priorities")
            prioritiesOp["value"] shouldBe JsonArray(listOf(JsonPrimitive("alpha"), JsonPrimitive("beta")))

            val motdOp = json[2].jsonObject
            motdOp["op"] shouldBe JsonPrimitive("\$set")
            motdOp["path"] shouldBe JsonPrimitive("$.listeners[0].motd")
            motdOp["value"] shouldBe JsonPrimitive("Welcome")

            val maxPlayersOp = json[3].jsonObject
            maxPlayersOp["op"] shouldBe JsonPrimitive("\$set")
            maxPlayersOp["path"] shouldBe JsonPrimitive("$.player_limit")
            maxPlayersOp["value"] shouldBe JsonPrimitive(20)
            maxPlayersOp["value-type"] shouldBe JsonPrimitive("int")

            val forwardingOp = json[4].jsonObject
            forwardingOp["op"] shouldBe JsonPrimitive("\$set")
            forwardingOp["path"] shouldBe JsonPrimitive("$.ip_forward")
            forwardingOp["value"] shouldBe JsonPrimitive(true)
            forwardingOp["value-type"] shouldBe JsonPrimitive("bool")
        }

        test("throws ConflictException for non-proxy server") {
            val nodeId = createNode()
            val vanillaId = createServer(nodeId, "vanilla-${Uuid.random()}", "VANILLA")
            shouldThrow<ConflictException> { service.generatePatch(vanillaId) }
        }

        test("throws NotFoundException for unknown server") {
            shouldThrow<NotFoundException> { service.generatePatch(Uuid.random()) }
        }

        test("generates Velocity patch with only backends (no proxy settings)") {
            val nodeId = createNode()
            val proxyId = createServer(nodeId, "proxy-${Uuid.random()}", "VELOCITY")
            val alphaId = createServer(nodeId, "alpha-${Uuid.random()}", "VANILLA")
            repos.proxyBackendRepository.replaceProxyBackends(
                proxyId,
                listOf(
                    ProxyBackendInput(alphaId, "alpha", 0)
                )
            )

            val patch = service.generatePatch(proxyId)
            val json = Json.parseToJsonElement(patch).jsonArray

            json.size shouldBe 1
            json[0].jsonObject["op"] shouldBe JsonPrimitive("\$set")
            json[0].jsonObject["path"] shouldBe JsonPrimitive("$.servers")
            val servers = json[0].jsonObject["value"]!!.jsonObject
            servers["alpha"] shouldBe JsonPrimitive("craftpanel-$alphaId:25565")
            servers["try"] shouldBe JsonArray(listOf(JsonPrimitive("alpha")))
        }
    })
