package io.craftpanel.master.service
import io.craftpanel.master.domain.ServerType
import io.craftpanel.master.service.repo.FakeRepositories
import io.craftpanel.master.service.repo.FakeServerRepository
import io.craftpanel.master.service.repo.FakeSettingsRepository
import io.craftpanel.master.service.repo.ServerRow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

private fun testServerRow(
    id: Uuid = Uuid.random(),
    networkId: Uuid? = null,
    exposedExternally: Boolean = false,
    publicSubdomain: String? = null,
    dnsRecordName: String? = null,
    customHostname: String? = null
) = ServerRow(
    id = id,
    name = "test-server",
    displayName = "test-server",
    description = null,
    nodeId = Uuid.random(),
    networkId = networkId,
    serverType = ServerType.VANILLA,
    mcVersion = "1.21.4",
    status = "STOPPED",
    hostPort = 25565,
    memoryMb = 1024,
    cpuShares = 0,
    exposedExternally = exposedExternally,
    publicSubdomain = publicSubdomain,
    dnsRecordId = null,
    dnsRecordName = dnsRecordName,
    customHostname = customHostname,
    configMode = "MANAGED",
    stopCommand = "stop",
    itzgImageTag = "latest",
    needsRecreate = false,
    backupSchedule = null,
    backupMaxCount = 0,
    backupScheduleLastFired = null,
    lastPlayerCount = null,
    lastPlayerNames = null,
    lastPlayerUpdate = null,
    lastSeenAt = null,
    createdAt = "2025-01-01T00:00:00Z",
    updatedAt = "2025-01-01T00:00:00Z"
)

class ServerExposureTest :
    FunSpec({
        lateinit var settingsRepository: FakeSettingsRepository
        lateinit var repos: FakeRepositories
        lateinit var serverRepository: FakeServerRepository
        lateinit var serverExposure: ServerExposure

        beforeTest {
            settingsRepository = FakeSettingsRepository()
            repos = FakeRepositories()
            serverRepository = FakeServerRepository(repos)
            serverExposure = ServerExposure(settingsRepository, serverRepository)
        }

        context("resolveSuffix") {
            test("returns the global setting when configured") {
                settingsRepository.addSetting("dns_domain_suffix", "global.example.com")
                serverExposure.resolveSuffix() shouldBe "global.example.com"
            }

            test("returns null when global setting not configured") {
                serverExposure.resolveSuffix()
                    .shouldBeNull()
            }
        }

        context("resolveGlobalDns") {
            test("returns null when zone or suffix missing") {
                serverExposure.resolveGlobalDns()
                    .shouldBeNull()
            }

            test("returns NetworkDns when zone and suffix configured") {
                settingsRepository.addSetting("dns_zone_id", "zone1")
                settingsRepository.addSetting("dns_domain_suffix", "net1.example.com")
                val dns = serverExposure.resolveGlobalDns()
                dns shouldBe ServerExposure.NetworkDns("zone1", "net1.example.com")
            }
        }

        context("managedHostname") {
            test("null when not exposed externally") {
                val row = testServerRow(exposedExternally = false, publicSubdomain = "play")
                serverExposure.managedHostname(row)
                    .shouldBeNull()
            }

            test("null when exposed but no subdomain") {
                val row = testServerRow(exposedExternally = true, publicSubdomain = null)
                serverExposure.managedHostname(row)
                    .shouldBeNull()
            }

            test("uses dnsRecordName when present") {
                val row = testServerRow(exposedExternally = true, publicSubdomain = "play", dnsRecordName = "play.example.com")
                serverExposure.managedHostname(row) shouldBe "play.example.com"
            }

            test("falls back to subdomain + resolved suffix when dnsRecordName absent") {
                settingsRepository.addSetting("dns_domain_suffix", "example.com")
                val row = testServerRow(exposedExternally = true, publicSubdomain = "play", dnsRecordName = null)
                serverExposure.managedHostname(row) shouldBe "play.example.com"
            }
        }

        context("mcRouterLabel") {
            test("null when neither managed nor custom hostname present") {
                val row = testServerRow()
                serverExposure.mcRouterLabel(row)
                    .shouldBeNull()
            }

            test("managed only") {
                val row = testServerRow(exposedExternally = true, publicSubdomain = "play", dnsRecordName = "play.example.com")
                serverExposure.mcRouterLabel(row) shouldBe "play.example.com"
            }

            test("custom only") {
                val row = testServerRow(customHostname = "custom.example.com")
                serverExposure.mcRouterLabel(row) shouldBe "custom.example.com"
            }

            test("both managed and custom, comma-joined") {
                val row = testServerRow(
                    exposedExternally = true,
                    publicSubdomain = "play",
                    dnsRecordName = "play.example.com",
                    customHostname = "custom.example.com"
                )
                serverExposure.mcRouterLabel(row) shouldBe "play.example.com,custom.example.com"
            }
        }

        context("canonicalHostname") {
            test("custom takes precedence over managed") {
                val row = testServerRow(
                    exposedExternally = true,
                    publicSubdomain = "play",
                    dnsRecordName = "play.example.com",
                    customHostname = "custom.example.com"
                )
                serverExposure.canonicalHostname(row) shouldBe "custom.example.com"
            }

            test("falls back to managed when no custom hostname") {
                val row = testServerRow(exposedExternally = true, publicSubdomain = "play", dnsRecordName = "play.example.com")
                serverExposure.canonicalHostname(row) shouldBe "play.example.com"
            }
        }

        context("validateCustomHostname") {
            test("rejects invalid RFC-1123 hostname") {
                shouldThrow<UnprocessableException> {
                    serverExposure.validateCustomHostname("not_a_valid_host!", Uuid.random())
                }
            }

            test("accepts valid RFC-1123 hostname") {
                serverExposure.validateCustomHostname("play.example.com", Uuid.random())
            }

            test("rejects collision with another server's custom hostname") {
                val otherId = Uuid.random()
                repos.servers[otherId] = FakeServerRepository.MutableServer(
                    id = otherId,
                    name = "other", displayName = "other", description = null,
                    nodeId = Uuid.random(), networkId = null, serverType = ServerType.VANILLA,
                    mcVersion = "1.21.4", itzgImageTag = "latest", hostPort = 25566,
                    memoryMb = 1024, cpuShares = 0, configMode = "MANAGED", stopCommand = "stop",
                    exposedExternally = true, customHostname = "taken.example.com"
                )
                shouldThrow<UnprocessableException> {
                    serverExposure.validateCustomHostname("taken.example.com", Uuid.random())
                }
            }

            test("allows a server to keep its own custom hostname (excludeServerId)") {
                val serverId = Uuid.random()
                repos.servers[serverId] = FakeServerRepository.MutableServer(
                    id = serverId,
                    name = "self", displayName = "self", description = null,
                    nodeId = Uuid.random(), networkId = null, serverType = ServerType.VANILLA,
                    mcVersion = "1.21.4", itzgImageTag = "latest", hostPort = 25567,
                    memoryMb = 1024, cpuShares = 0, configMode = "MANAGED", stopCommand = "stop",
                    exposedExternally = true, customHostname = "self.example.com"
                )
                serverExposure.validateCustomHostname("self.example.com", serverId)
            }

            test("rejects collision with a managed DNS record name") {
                val otherId = Uuid.random()
                repos.servers[otherId] = FakeServerRepository.MutableServer(
                    id = otherId,
                    name = "other2", displayName = "other2", description = null,
                    nodeId = Uuid.random(), networkId = null, serverType = ServerType.VANILLA,
                    mcVersion = "1.21.4", itzgImageTag = "latest", hostPort = 25568,
                    memoryMb = 1024, cpuShares = 0, configMode = "MANAGED", stopCommand = "stop",
                    exposedExternally = true, publicSubdomain = "play",
                    dnsRecordId = "rec1", dnsRecordName = "play.example.com"
                )
                shouldThrow<UnprocessableException> {
                    serverExposure.validateCustomHostname("play.example.com", Uuid.random())
                }
            }

            test("rejects hostname under the global managed suffix") {
                settingsRepository.addSetting("dns_domain_suffix", "global.example.com")
                shouldThrow<UnprocessableException> {
                    serverExposure.validateCustomHostname("sub.global.example.com", Uuid.random())
                }
            }
        }
    })
