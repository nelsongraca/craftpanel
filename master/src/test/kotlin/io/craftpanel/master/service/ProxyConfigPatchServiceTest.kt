package io.craftpanel.master.service

import io.craftpanel.master.TestDatabase
import io.craftpanel.master.TestRepositories
import io.craftpanel.master.database.entity.Server
import io.craftpanel.master.database.schema.*
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
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
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
                it[Nodes.totalCpuMillicores] = 0
                it[Nodes.portRangeStart] = 25565
                it[Nodes.portRangeEnd] = 25600
            }[Nodes.id].let { Uuid.parse(it.toString()) }
        }

        fun createServer(nodeId: Uuid, name: String, type: ServerType): Uuid = transaction {
            Server.new {
                this.name = name
                this.displayName = name
                this.description = null
                this.nodeId = EntityID(nodeId, Nodes)
                this.networkId = null
                this.serverType = type.toDb()
                this.mcVersion = "1.21.4"
                this.itzgImageTag = "latest"
                this.hostPort = 25565
                this.memoryMb = 1024
                this.cpuLimitMillicores = 0
                this.configMode = "MANAGED"
                this.stopCommand = "stop"
            }.id.value
        }

        fun opsOf(patch: String): List<JsonObject> {
            val root = Json.parseToJsonElement(patch).jsonObject
            val patches = root["patches"]!!.jsonArray
            patches.size shouldBe 1
            return patches[0].jsonObject["ops"]!!.jsonArray.map { it.jsonObject }
        }

        test("generates Velocity patch with all settings and backends") {
            val nodeId = createNode()
            val proxyId = createServer(nodeId, "proxy-${Uuid.random()}", ServerType.VELOCITY)
            transaction {
                val e = Server.findById(proxyId) ?: return@transaction
                e.proxyMotd = "Welcome"
                e.proxyMaxPlayers = 20
                e.proxyForwardingMode = "LEGACY"
                e.proxyProtocol = true
            }

            val alphaName = "alpha-${Uuid.random()}"
            val betaName = "beta-${Uuid.random()}"
            val alphaId = createServer(nodeId, alphaName, ServerType.VANILLA)
            val betaId = createServer(nodeId, betaName, ServerType.PAPER)
            transaction {
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = EntityID(proxyId, Servers)
                    it[ProxyBackends.backendServerId] = EntityID(alphaId, Servers)
                    it[ProxyBackends.backendName] =
                        "alpha"
                    it[ProxyBackends.order] = 0
                }
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = EntityID(proxyId, Servers)
                    it[ProxyBackends.backendServerId] = EntityID(betaId, Servers)
                    it[ProxyBackends.backendName] =
                        "beta"
                    it[ProxyBackends.order] = 1
                }
            }

            val patch = service.generatePatch(proxyId)!!
            val root = Json.parseToJsonElement(patch).jsonObject
            root["patches"]!!.jsonArray[0].jsonObject["file"] shouldBe JsonPrimitive("/server/velocity.toml")
            val ops = opsOf(patch)

            ops.size shouldBe 6

            val serversOp = ops[0]["\$set"]!!.jsonObject
            serversOp["path"] shouldBe JsonPrimitive("$.servers")
            val servers = serversOp["value"]!!.jsonObject
            servers["alpha"] shouldBe JsonPrimitive("$alphaName:25565")
            servers["beta"] shouldBe JsonPrimitive("$betaName:25565")
            servers["try"] shouldBe JsonArray(listOf(JsonPrimitive("alpha"), JsonPrimitive("beta")))

            val forcedHostsOp = ops[1]["\$set"]!!.jsonObject
            forcedHostsOp["path"] shouldBe JsonPrimitive("\$['forced-hosts']")
            forcedHostsOp["value"] shouldBe JsonObject(emptyMap())

            val motdOp = ops[2]["\$set"]!!.jsonObject
            motdOp["path"] shouldBe JsonPrimitive("$.motd")
            motdOp["value"] shouldBe JsonPrimitive("Welcome")

            val maxPlayersOp = ops[3]["\$set"]!!.jsonObject
            maxPlayersOp["path"] shouldBe JsonPrimitive("\$['show-max-players']")
            maxPlayersOp["value"] shouldBe JsonPrimitive(20)
            maxPlayersOp["value-type"] shouldBe JsonPrimitive("int")

            val forwardingOp = ops[4]["\$set"]!!.jsonObject
            forwardingOp["path"] shouldBe JsonPrimitive("\$['player-info-forwarding-mode']")
            forwardingOp["value"] shouldBe JsonPrimitive("legacy")

            val proxyProtocolOp = ops[5]["\$set"]!!.jsonObject
            proxyProtocolOp["path"] shouldBe JsonPrimitive("\$['haproxy-protocol']")
            proxyProtocolOp["value"] shouldBe JsonPrimitive(true)
            proxyProtocolOp["value-type"] shouldBe JsonPrimitive("bool")
        }

        test("generates BungeeCord patch with all settings and backends") {
            val nodeId = createNode()
            val proxyId = createServer(nodeId, "proxy-${Uuid.random()}", ServerType.BUNGEECORD)
            transaction {
                val e = Server.findById(proxyId) ?: return@transaction
                e.proxyMotd = "Welcome"
                e.proxyMaxPlayers = 20
                e.proxyForwardingMode = "LEGACY"
                e.proxyProtocol = true
            }

            val alphaName = "alpha-${Uuid.random()}"
            val betaName = "beta-${Uuid.random()}"
            val alphaId = createServer(nodeId, alphaName, ServerType.VANILLA)
            val betaId = createServer(nodeId, betaName, ServerType.PAPER)
            transaction {
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = EntityID(proxyId, Servers)
                    it[ProxyBackends.backendServerId] = EntityID(alphaId, Servers)
                    it[ProxyBackends.backendName] =
                        "alpha"
                    it[ProxyBackends.order] = 0
                }
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = EntityID(proxyId, Servers)
                    it[ProxyBackends.backendServerId] = EntityID(betaId, Servers)
                    it[ProxyBackends.backendName] =
                        "beta"
                    it[ProxyBackends.order] = 1
                }
            }

            val patch = service.generatePatch(proxyId)!!
            val root = Json.parseToJsonElement(patch).jsonObject
            root["patches"]!!.jsonArray[0].jsonObject["file"] shouldBe JsonPrimitive("/server/config.yml")
            val ops = opsOf(patch)

            ops.size shouldBe 7

            val serversOp = ops[0]["\$set"]!!.jsonObject
            serversOp["path"] shouldBe JsonPrimitive("$.servers")
            val servers = serversOp["value"]!!.jsonObject
            servers["alpha"] shouldBe JsonObject(
                mapOf(
                    "address" to JsonPrimitive("$alphaName:25565"),
                    "restricted" to JsonPrimitive(false)
                )
            )
            servers["beta"] shouldBe JsonObject(
                mapOf(
                    "address" to JsonPrimitive("$betaName:25565"),
                    "restricted" to JsonPrimitive(false)
                )
            )

            val forcedHostsOp = ops[1]["\$set"]!!.jsonObject
            forcedHostsOp["path"] shouldBe JsonPrimitive("$.listeners[0].forced_hosts")
            forcedHostsOp["value"] shouldBe JsonObject(emptyMap())

            val prioritiesOp = ops[2]["\$set"]!!.jsonObject
            prioritiesOp["path"] shouldBe JsonPrimitive("$.listeners[0].priorities")
            prioritiesOp["value"] shouldBe JsonArray(listOf(JsonPrimitive("alpha"), JsonPrimitive("beta")))

            val motdOp = ops[3]["\$set"]!!.jsonObject
            motdOp["path"] shouldBe JsonPrimitive("$.listeners[0].motd")
            motdOp["value"] shouldBe JsonPrimitive("Welcome")

            val maxPlayersOp = ops[4]["\$set"]!!.jsonObject
            maxPlayersOp["path"] shouldBe JsonPrimitive("$.player_limit")
            maxPlayersOp["value"] shouldBe JsonPrimitive(20)
            maxPlayersOp["value-type"] shouldBe JsonPrimitive("int")

            val forwardingOp = ops[5]["\$set"]!!.jsonObject
            forwardingOp["path"] shouldBe JsonPrimitive("$.ip_forward")
            forwardingOp["value"] shouldBe JsonPrimitive(true)
            forwardingOp["value-type"] shouldBe JsonPrimitive("bool")

            val proxyProtocolOp = ops[6]["\$set"]!!.jsonObject
            proxyProtocolOp["path"] shouldBe JsonPrimitive("$.listeners[0].proxy_protocol")
            proxyProtocolOp["value"] shouldBe JsonPrimitive(true)
            proxyProtocolOp["value-type"] shouldBe JsonPrimitive("bool")
        }

        test("WATERFALL uses the BungeeCord proxy-protocol path") {
            val nodeId = createNode()
            val proxyId = createServer(nodeId, "proxy-${Uuid.random()}", ServerType.WATERFALL)
            transaction { Server.findById(proxyId)?.let { it.proxyProtocol = true } }

            val ops = opsOf(service.generatePatch(proxyId)!!)

            // Waterfall is a BungeeCord fork — same config.yml shape, so it must use the same path
            // and not be mistaken for Velocity.
            val op = ops.last()["\$set"]!!.jsonObject
            op["path"] shouldBe JsonPrimitive("$.listeners[0].proxy_protocol")
            op["value"] shouldBe JsonPrimitive(true)
            op["value-type"] shouldBe JsonPrimitive("bool")
        }

        test("MANUAL config mode - returns null") {
            val nodeId = createNode()
            val proxyId = createServer(nodeId, "proxy-${Uuid.random()}", ServerType.VELOCITY)
            transaction { Server.findById(proxyId)?.let { it.configMode = "MANUAL" } }

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
            val alphaName = "alpha-${Uuid.random()}"
            val alphaId = createServer(nodeId, alphaName, ServerType.VANILLA)
            transaction {
                ProxyBackends.insert {
                    it[ProxyBackends.proxyServerId] = EntityID(proxyId, Servers)
                    it[ProxyBackends.backendServerId] = EntityID(alphaId, Servers)
                    it[ProxyBackends.backendName] =
                        "alpha"
                    it[ProxyBackends.order] = 0
                }
            }

            val patch = service.generatePatch(proxyId)!!
            val ops = opsOf(patch)

            ops.size shouldBe 3
            val serversOp = ops[0]["\$set"]!!.jsonObject
            serversOp["path"] shouldBe JsonPrimitive("$.servers")
            val servers = serversOp["value"]!!.jsonObject
            servers["alpha"] shouldBe JsonPrimitive("$alphaName:25565")
            servers["try"] shouldBe JsonArray(listOf(JsonPrimitive("alpha")))

            val forcedHostsOp = ops[1]["\$set"]!!.jsonObject
            forcedHostsOp["path"] shouldBe JsonPrimitive("\$['forced-hosts']")
            forcedHostsOp["value"] shouldBe JsonObject(emptyMap())

            val proxyProtocolOp = ops[2]["\$set"]!!.jsonObject
            proxyProtocolOp["path"] shouldBe JsonPrimitive("\$['haproxy-protocol']")
            proxyProtocolOp["value"] shouldBe JsonPrimitive(false)
            proxyProtocolOp["value-type"] shouldBe JsonPrimitive("bool")
        }
    })
