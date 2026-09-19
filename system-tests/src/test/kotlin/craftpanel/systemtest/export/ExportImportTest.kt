package craftpanel.systemtest.export

import craftpanel.systemtest.client.model.*
import craftpanel.systemtest.harness.BaseSystemTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKeys
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.openapitools.client.infrastructure.ClientException

@Tags("Misc")
class ExportImportTest : BaseSystemTest() {

    private lateinit var sourceServerId: String
    private lateinit var sourceExport: ServerExportData
    private lateinit var sourceName: String

    init {

        beforeSpec {
            sourceName = "export-test-${System.currentTimeMillis()}"
            sourceServerId = api.createServer(
                CreateServerRequest(
                    name = sourceName,
                    displayName = "Export Test Display",
                    description = "A server for export testing",
                    nodeId = nodeId,
                    serverType = "PAPER",
                    mcVersion = "1.21.4",
                    itzgImageTag = "latest",
                    memoryMb = 1024,
                    cpuLimitMillicores = 1000,
                    containerListenPort = 25565,
                    containerProtocol = "UDP",
                    disableHealthcheck = true,
                    forceRedownload = true
                )
            ).id

            api.updateStopCommand(sourceServerId, PatchStopCommandRequest(stopCommand = "save-all"))
            api.updateServerExposure(sourceServerId, PatchExposureRequest(exposedExternally = true, customHostname = "my-server.example.com"))
            api.updateServerExpiration(sourceServerId, PatchExpirationRequest(expiresAt = "2027-12-31T23:59:59Z"))

            api.replaceEnvVars(
                sourceServerId,
                PutEnvVarsRequest(
                    envVars = listOf(
                        EnvVarItem(key = "DIFFICULTY", value = "hard"),
                        EnvVarItem(key = "MODE", value = "creative"),
                        EnvVarItem(key = "PVP", value = "false"),
                        EnvVarItem(key = "MAX_PLAYERS", value = "10"),
                        EnvVarItem(key = "ALLOW_FLIGHT", value = "true"),
                        EnvVarItem(key = "SPAWN_PROTECTION", value = "0"),
                        EnvVarItem(key = "ONLINE_MODE", value = "false")
                    )
                )
            )

            sourceExport = api.exportServer(sourceServerId)
        }

        afterSpec {
            runCatching { api.deleteServer(sourceServerId) }
        }

        context("Server export/import") {

            should("include every config field with correct values in an export") {
                sourceExport.name shouldBe sourceName
                sourceExport.displayName shouldBe "Export Test Display"
                sourceExport.description shouldBe "A server for export testing"
                sourceExport.serverType shouldBe "PAPER"
                sourceExport.mcVersion shouldBe "1.21.4"
                sourceExport.itzgImageTag shouldBe "latest"
                sourceExport.memoryMb shouldBe 1024
                sourceExport.cpuLimitMillicores shouldBe 1000
                sourceExport.stopCommand shouldBe "save-all"
                sourceExport.exposedExternally shouldBe true
                sourceExport.customHostname shouldBe "my-server.example.com"
                sourceExport.configMode shouldBe "MANAGED"
                sourceExport.containerListenPort shouldBe 25565
                sourceExport.containerProtocol shouldBe "UDP"
                sourceExport.disableHealthcheck shouldBe true
                sourceExport.forceRedownload shouldBe true
                sourceExport.expiresAt shouldBe "2027-12-31T23:59:59Z"
                sourceExport.customServerJar.shouldBeNull()
            }

            should("include all env vars with correct values in an export") {
                val envVars = sourceExport.envVars ?: emptyList()
                val byKey = envVars.associate { it.key to it.`value` }
                byKey["DIFFICULTY"] shouldBe "hard"
                byKey["MODE"] shouldBe "creative"
                byKey["PVP"] shouldBe "false"
                byKey["MAX_PLAYERS"] shouldBe "10"
                byKey["ALLOW_FLIGHT"] shouldBe "true"
                byKey["SPAWN_PROTECTION"] shouldBe "0"
                byKey["ONLINE_MODE"] shouldBe "false"
            }

            should("preserve every field through a full round-trip: export, delete source, import back") {
                api.deleteServer(sourceServerId)
                helper.awaitStoppedOrGone(sourceServerId)

                val imported = api.importServer(
                    ImportServerRequest(
                        `data` = sourceExport,
                        nodeId = nodeId
                    )
                )

                try {
                    imported.name shouldBe sourceName
                    imported.displayName shouldBe "Export Test Display"
                    imported.description shouldBe "A server for export testing"
                    imported.serverType shouldBe "PAPER"
                    imported.mcVersion shouldBe "1.21.4"
                    imported.itzgImageTag shouldBe "latest"
                    imported.memoryMb shouldBe 1024
                    imported.cpuLimitMillicores shouldBe 1000
                    imported.stopCommand shouldBe "save-all"
                    imported.exposedExternally shouldBe true
                    imported.customHostname shouldBe "my-server.example.com"
                    imported.configMode shouldBe ConfigMode.MANAGED
                    imported.containerListenPort shouldBe 25565
                    imported.containerProtocol shouldBe "UDP"
                    imported.disableHealthcheck shouldBe true
                    imported.forceRedownload shouldBe true
                    imported.expiresAt shouldBe "2027-12-31T23:59:59Z"
                    imported.disabled shouldBe false

                    val envVars = api.getEnvVars(imported.id)
                    val byKey = envVars.envVars.associate { it.key to it.value }
                    byKey["DIFFICULTY"] shouldBe "hard"
                    byKey["MODE"] shouldBe "creative"
                    byKey["PVP"] shouldBe "false"
                    byKey["MAX_PLAYERS"] shouldBe "10"
                    byKey["ALLOW_FLIGHT"] shouldBe "true"
                    byKey["SPAWN_PROTECTION"] shouldBe "0"
                    byKey["ONLINE_MODE"] shouldBe "false"

                    sourceServerId = imported.id
                } finally {
                    runCatching { api.deleteServer(imported.id) }
                }
            }

            should("return 404 when exporting a non-existent server") {
                val ex = shouldThrow<ClientException> { api.exportServer("00000000-0000-0000-0000-000000000000") }
                ex.statusCode shouldBe 404
            }

            should("return 422 when importing without node_id") {
                val ex = shouldThrow<ClientException> {
                    api.importServer(
                        ImportServerRequest(
                            `data` = sourceExport.copy(name = "no-node-$sourceName"),
                            nodeId = ""
                        )
                    )
                }
                ex.statusCode shouldBe 422
            }
        }

        context("Network export/import") {

            should("recreate everything by exporting a network with 2 servers and re-importing") {
                val ts = System.currentTimeMillis()
                val netName = "export-net-$ts"

                val s1Id = helper.createTestServer(nodeId, memoryMb = 512, cpuLimitMillicores = 0)
                val s2Id = helper.createTestServer(nodeId, memoryMb = 384, cpuLimitMillicores = 0)

                try {
                    val net = api.createNetwork(
                        CreateNetworkRequest(name = netName, description = "Network export test")
                    )
                    api.updateServer(s1Id, UpdateServerRequest(networkId = net.id))
                    api.updateServer(s2Id, UpdateServerRequest(networkId = net.id))

                    api.replaceEnvVars(
                        s1Id,
                        PutEnvVarsRequest(
                            envVars = listOf(EnvVarItem(key = "DIFFICULTY", value = "hard"))
                        )
                    )

                    val export = api.exportNetwork(net.id)
                    export.name shouldBe netName
                    export.servers!! shouldHaveSize 2

                    val s1Export = export.servers!!.first { it.memoryMb == 512 }
                    s1Export.serverType shouldBe "PAPER"
                    s1Export.envVars?.let { envs ->
                        envs.first { it.key == "DIFFICULTY" }.`value` shouldBe "hard"
                    }

                    runCatching { api.deleteServer(s1Id) }
                    runCatching { api.deleteServer(s2Id) }
                    helper.awaitStoppedOrGone(s1Id)
                    helper.awaitStoppedOrGone(s2Id)
                    api.deleteNetwork(net.id)

                    val importedNet = api.importNetwork(
                        ImportNetworkRequest(
                            data = export,
                            nodeAssignments = export.servers!!.associate { it.name to nodeId }
                        )
                    )
                    importedNet.name shouldBe netName
                    importedNet.serverCount shouldBe 2

                    val members = api.getNetwork(importedNet.id)
                    members.servers shouldHaveSize 2

                    val s1Detail = api.getServer(members.servers[0].id)
                    val s2Detail = api.getServer(members.servers[1].id)
                    val bigServer = if (s1Detail.memoryMb == 512) s1Detail else s2Detail
                    val envVars = api.getEnvVars(bigServer.id)

                    runCatching { api.deleteServer(members.servers[0].id) }
                    runCatching { api.deleteServer(members.servers[1].id) }
                    helper.awaitStoppedOrGone(members.servers[0].id)
                    helper.awaitStoppedOrGone(members.servers[1].id)
                    api.deleteNetwork(importedNet.id)
                } finally {
                    runCatching { api.deleteServer(s1Id) }
                    runCatching { api.deleteServer(s2Id) }
                }
            }
        }
    }
}
