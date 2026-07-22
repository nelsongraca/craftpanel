package io.craftpanel.master.service

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.ProxyBackendInput
import io.craftpanel.master.service.repo.ServerRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

        fun createServer(nodeId: Uuid, name: String, type: ServerType): Uuid = serverRepository.create(
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

        fun opsOf(patch: String): List<JsonObject> {
            val root = Json.parseToJsonElement(patch).jsonObject
            val patches = root["patches"]!!.jsonArray
            patches.size shouldBe 1
            return patches[0].jsonObject["ops"]!!.jsonArray.map { it.jsonObject }
        }

        test("generates Velocity patch with all settings and backends") {
            val nodeId = createNode()
            val proxyId = createServer(nodeId, "proxy-${Uuid.random()}", ServerType.VELOCITY)
            serverRepository.updateProxySettings(proxyId, "Welcome", 20, "LEGACY")

            val alphaId = createServer(nodeId, "alpha-${Uuid.random()}", ServerType.VANILLA)
            val betaId = createServer(nodeId, "beta-${Uuid.random()}", ServerType.PAPER)
            repos.proxyBackendRepository.replaceProxyBackends(
                proxyId,
                listOf(
                    ProxyBackendInput(alphaId, "alpha", 0),
                    ProxyBackendInput(betaId, "beta", 1)
                )
            )

            val patch = service.generatePatch(proxyId)!!
            val root = Json.parseToJsonElement(patch).jsonObject
            root["patches"]!!.jsonArray[0].jsonObject["file"] shouldBe JsonPrimitive("/server/velocity.toml")
            val ops = opsOf(patch)

            ops.size shouldBe 4

            val serversOp = ops[0]["\$set"]!!.jsonObject
            serversOp["path"] shouldBe JsonPrimitive("$.servers")
            val servers = serversOp["value"]!!.jsonObject
            servers["alpha"] shouldBe JsonPrimitive("craftpanel-$alphaId:25565")
            servers["beta"] shouldBe JsonPrimitive("craftpanel-$betaId:25565")
            servers["try"] shouldBe JsonArray(listOf(JsonPrimitive("alpha"), JsonPrimitive("beta")))

            val motdOp = ops[1]["\$set"]!!.jsonObject
            motdOp["path"] shouldBe JsonPrimitive("$.motd")
            motdOp["value"] shouldBe JsonPrimitive("Welcome")

            val maxPlayersOp = ops[2]["\$set"]!!.jsonObject
            maxPlayersOp["path"] shouldBe JsonPrimitive("\$['show-max-players']")
            maxPlayersOp["value"] shouldBe JsonPrimitive(20)
            maxPlayersOp["value-type"] shouldBe JsonPrimitive("int")

            val forwardingOp = ops[3]["\$set"]!!.jsonObject
            forwardingOp["path"] shouldBe JsonPrimitive("\$['player-info-forwarding-mode']")
            forwardingOp["value"] shouldBe JsonPrimitive("legacy")
        }

        test("generates BungeeCord patch with all settings and backends") {
            val nodeId = createNode()
            val proxyId = createServer(nodeId, "proxy-${Uuid.random()}", ServerType.BUNGEECORD)
            serverRepository.updateProxySettings(proxyId, "Welcome", 20, "LEGACY")

            val alphaId = createServer(nodeId, "alpha-${Uuid.random()}", ServerType.VANILLA)
            val betaId = createServer(nodeId, "beta-${Uuid.random()}", ServerType.PAPER)
            repos.proxyBackendRepository.replaceProxyBackends(
                proxyId,
                listOf(
                    ProxyBackendInput(alphaId, "alpha", 0),
                    ProxyBackendInput(betaId, "beta", 1)
                )
            )

            val patch = service.generatePatch(proxyId)!!
            val root = Json.parseToJsonElement(patch).jsonObject
            root["patches"]!!.jsonArray[0].jsonObject["file"] shouldBe JsonPrimitive("/server/config.yml")
            val ops = opsOf(patch)

            ops.size shouldBe 5

            val serversOp = ops[0]["\$set"]!!.jsonObject
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

            val prioritiesOp = ops[1]["\$set"]!!.jsonObject
            prioritiesOp["path"] shouldBe JsonPrimitive("$.listeners[0].priorities")
            prioritiesOp["value"] shouldBe JsonArray(listOf(JsonPrimitive("alpha"), JsonPrimitive("beta")))

            val motdOp = ops[2]["\$set"]!!.jsonObject
            motdOp["path"] shouldBe JsonPrimitive("$.listeners[0].motd")
            motdOp["value"] shouldBe JsonPrimitive("Welcome")

            val maxPlayersOp = ops[3]["\$set"]!!.jsonObject
            maxPlayersOp["path"] shouldBe JsonPrimitive("$.player_limit")
            maxPlayersOp["value"] shouldBe JsonPrimitive(20)
            maxPlayersOp["value-type"] shouldBe JsonPrimitive("int")

            val forwardingOp = ops[4]["\$set"]!!.jsonObject
            forwardingOp["path"] shouldBe JsonPrimitive("$.ip_forward")
            forwardingOp["value"] shouldBe JsonPrimitive(true)
            forwardingOp["value-type"] shouldBe JsonPrimitive("bool")
        }

        test("MANUAL config mode - returns null") {
            val nodeId = createNode()
            val proxyId = createServer(nodeId, "proxy-${Uuid.random()}", ServerType.VELOCITY)
            serverRepository.updateConfigMode(proxyId, "MANUAL")

            service.generatePatch(proxyId) shouldBe null
        }

        test("MANAGED config mode - returns the patch") {
            val nodeId = createNode()
            val proxyId = createServer(nodeId, "proxy-${Uuid.random()}", ServerType.VELOCITY)

            service.generatePatch(proxyId) shouldNotBe null
        }

        test("throws ConflictException for non-proxy server") {
            val nodeId = createNode()
            val vanillaId = createServer(nodeId, "vanilla-${Uuid.random()}", ServerType.VANILLA)
            shouldThrow<ConflictException> { service.generatePatch(vanillaId) }
        }

        test("throws NotFoundException for unknown server") {
            shouldThrow<NotFoundException> { service.generatePatch(Uuid.random()) }
        }

        test("generates Velocity patch with only backends (no proxy settings)") {
            val nodeId = createNode()
            val proxyId = createServer(nodeId, "proxy-${Uuid.random()}", ServerType.VELOCITY)
            val alphaId = createServer(nodeId, "alpha-${Uuid.random()}", ServerType.VANILLA)
            repos.proxyBackendRepository.replaceProxyBackends(
                proxyId,
                listOf(
                    ProxyBackendInput(alphaId, "alpha", 0)
                )
            )

            val patch = service.generatePatch(proxyId)!!
            val ops = opsOf(patch)

            ops.size shouldBe 1
            val serversOp = ops[0]["\$set"]!!.jsonObject
            serversOp["path"] shouldBe JsonPrimitive("$.servers")
            val servers = serversOp["value"]!!.jsonObject
            servers["alpha"] shouldBe JsonPrimitive("craftpanel-$alphaId:25565")
            servers["try"] shouldBe JsonArray(listOf(JsonPrimitive("alpha")))
        }
    })
