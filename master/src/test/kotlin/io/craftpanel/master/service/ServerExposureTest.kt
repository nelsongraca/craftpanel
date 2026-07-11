package io.craftpanel.master.service

import io.craftpanel.master.service.repo.FakeNetworkRepository
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
    customHostname: String? = null,
) = ServerRow(
    id = id,
    name = "test-server",
    displayName = "test-server",
    description = null,
    nodeId = Uuid.random(),
    networkId = networkId,
    serverType = "VANILLA",
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
    updatedAt = "2025-01-01T00:00:00Z",
)

class ServerExposureTest : FunSpec({
    lateinit var networkRepository: FakeNetworkRepository
    lateinit var settingsRepository: FakeSettingsRepository
    lateinit var serverRepository: FakeServerRepository
    lateinit var serverExposure: ServerExposure

    beforeTest {
        networkRepository = FakeNetworkRepository()
        settingsRepository = FakeSettingsRepository()
        serverRepository = FakeServerRepository(FakeRepositories())
        serverExposure = ServerExposure(networkRepository, settingsRepository, serverRepository)
    }

    context("resolveSuffix") {
        test("falls back to global setting when network has no suffix") {
            settingsRepository.upsert("dns_domain_suffix", "global.example.com", null, null)
            serverExposure.resolveSuffix(null) shouldBe "global.example.com"
        }

        test("prefers network suffix over global setting") {
            settingsRepository.upsert("dns_domain_suffix", "global.example.com", null, null)
            val network = networkRepository.create(
                name = "net1", proxyPort = null, description = null,
                cfDomainSuffix = "net1.example.com", cfZoneId = "zone1", dnsProviderType = "CLOUDFLARE",
            )
            serverExposure.resolveSuffix(network.id) shouldBe "net1.example.com"
        }

        test("returns null when neither network nor global setting resolve") {
            serverExposure.resolveSuffix(null).shouldBeNull()
        }
    }

    context("resolveNetworkDns") {
        test("returns null for null networkId") {
            serverExposure.resolveNetworkDns(null).shouldBeNull()
        }

        test("returns null when network has no zone or suffix") {
            val network = networkRepository.create(
                name = "net1", proxyPort = null, description = null,
                cfDomainSuffix = null, cfZoneId = null, dnsProviderType = null,
            )
            serverExposure.resolveNetworkDns(network.id).shouldBeNull()
        }

        test("returns NetworkDns when network has zone and suffix") {
            val network = networkRepository.create(
                name = "net1", proxyPort = null, description = null,
                cfDomainSuffix = "net1.example.com", cfZoneId = "zone1", dnsProviderType = "CLOUDFLARE",
            )
            val dns = serverExposure.resolveNetworkDns(network.id)
            dns shouldBe ServerExposure.NetworkDns("zone1", "net1.example.com")
        }
    }

    context("managedHostname") {
        test("null when not exposed externally") {
            val row = testServerRow(exposedExternally = false, publicSubdomain = "play")
            serverExposure.managedHostname(row).shouldBeNull()
        }

        test("null when exposed but no subdomain") {
            val row = testServerRow(exposedExternally = true, publicSubdomain = null)
            serverExposure.managedHostname(row).shouldBeNull()
        }

        test("uses dnsRecordName when present") {
            val row = testServerRow(exposedExternally = true, publicSubdomain = "play", dnsRecordName = "play.example.com")
            serverExposure.managedHostname(row) shouldBe "play.example.com"
        }

        test("falls back to subdomain + resolved suffix when dnsRecordName absent") {
            settingsRepository.upsert("dns_domain_suffix", "example.com", null, null)
            val row = testServerRow(exposedExternally = true, publicSubdomain = "play", dnsRecordName = null)
            serverExposure.managedHostname(row) shouldBe "play.example.com"
        }
    }

    context("mcRouterLabel") {
        test("null when neither managed nor custom hostname present") {
            val row = testServerRow()
            serverExposure.mcRouterLabel(row).shouldBeNull()
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
                exposedExternally = true, publicSubdomain = "play", dnsRecordName = "play.example.com",
                customHostname = "custom.example.com",
            )
            serverExposure.mcRouterLabel(row) shouldBe "play.example.com,custom.example.com"
        }
    }

    context("canonicalHostname") {
        test("custom takes precedence over managed") {
            val row = testServerRow(
                exposedExternally = true, publicSubdomain = "play", dnsRecordName = "play.example.com",
                customHostname = "custom.example.com",
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
            val other = serverRepository.create(
                name = "other", displayName = "other", description = null,
                nodeId = Uuid.random(), networkId = null, serverType = "VANILLA",
                mcVersion = "1.21.4", itzgImageTag = "latest", hostPort = 25566,
                memoryMb = 1024, cpuShares = 0, configMode = "MANAGED", stopCommand = "stop",
            )
            serverRepository.updateExposure(
                id = other.id, exposedExternally = true, publicSubdomain = null,
                customHostname = "taken.example.com", dnsRecordId = null, dnsRecordName = null,
                needsRecreate = null,
            )
            shouldThrow<UnprocessableException> {
                serverExposure.validateCustomHostname("taken.example.com", Uuid.random())
            }
        }

        test("allows a server to keep its own custom hostname (excludeServerId)") {
            val server = serverRepository.create(
                name = "self", displayName = "self", description = null,
                nodeId = Uuid.random(), networkId = null, serverType = "VANILLA",
                mcVersion = "1.21.4", itzgImageTag = "latest", hostPort = 25567,
                memoryMb = 1024, cpuShares = 0, configMode = "MANAGED", stopCommand = "stop",
            )
            serverRepository.updateExposure(
                id = server.id, exposedExternally = true, publicSubdomain = null,
                customHostname = "self.example.com", dnsRecordId = null, dnsRecordName = null,
                needsRecreate = null,
            )
            serverExposure.validateCustomHostname("self.example.com", server.id)
        }

        test("rejects collision with a managed DNS record name") {
            val other = serverRepository.create(
                name = "other2", displayName = "other2", description = null,
                nodeId = Uuid.random(), networkId = null, serverType = "VANILLA",
                mcVersion = "1.21.4", itzgImageTag = "latest", hostPort = 25568,
                memoryMb = 1024, cpuShares = 0, configMode = "MANAGED", stopCommand = "stop",
            )
            serverRepository.updateExposure(
                id = other.id, exposedExternally = true, publicSubdomain = "play",
                customHostname = null, dnsRecordId = "rec1", dnsRecordName = "play.example.com",
                needsRecreate = null,
            )
            shouldThrow<UnprocessableException> {
                serverExposure.validateCustomHostname("play.example.com", Uuid.random())
            }
        }

        test("rejects hostname under a panel-managed network suffix") {
            networkRepository.create(
                name = "net1", proxyPort = null, description = null,
                cfDomainSuffix = "managed.example.com", cfZoneId = "zone1", dnsProviderType = "CLOUDFLARE",
            )
            shouldThrow<UnprocessableException> {
                serverExposure.validateCustomHostname("sub.managed.example.com", Uuid.random())
            }
        }

        test("rejects hostname under the global managed suffix") {
            settingsRepository.upsert("dns_domain_suffix", "global.example.com", null, null)
            shouldThrow<UnprocessableException> {
                serverExposure.validateCustomHostname("sub.global.example.com", Uuid.random())
            }
        }
    }
})
