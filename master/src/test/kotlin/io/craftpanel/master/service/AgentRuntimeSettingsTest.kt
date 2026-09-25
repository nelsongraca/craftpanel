package io.craftpanel.master.service

import io.craftpanel.master.TestAgentGateway
import io.craftpanel.master.TestDatabase
import io.craftpanel.master.auth.Argon2Hasher
import io.craftpanel.master.createTestControlServiceImpl
import io.craftpanel.master.config.DnsConfig
import io.craftpanel.master.crypto.SecretCipher
import io.craftpanel.master.database.schema.Nodes
import io.craftpanel.master.database.schema.SystemSettings
import io.craftpanel.master.database.schema.Users
import io.craftpanel.master.dns.DnsProvider
import io.craftpanel.master.dns.DnsProviderResolver
import io.craftpanel.master.service.repo.impl.NodeRepositoryImpl
import io.craftpanel.master.service.repo.impl.SettingsRepositoryImpl
import io.craftpanel.proto.nodeMetadata
import io.craftpanel.proto.registerNodeRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class AgentRuntimeSettingsTest :
    FunSpec({

        val cipher = SecretCipher(ByteArray(32) { 0x42 })

        fun settingsProvider(): SettingsProvider = SettingsProvider(SettingsRepositoryImpl())

        fun newUser(): Uuid = transaction {
            Users.insert {
                it[username] = "u-${Uuid.random()}"
                it[email] = "${Uuid.random()}@example.com"
                it[passwordHash] = Argon2Hasher.hash("pass")
            }[Users.id].value
        }

        fun rawValue(key: String): String? = transaction {
            SystemSettings.selectAll()
                .where { SystemSettings.key eq key }
                .firstOrNull()
                ?.get(SystemSettings.value)
        }

        fun systemService(
            provider: SettingsProvider = settingsProvider(),
            pushRuntimeSettings: () -> Unit = {},
            dnsFactory: (DnsConfig) -> DnsProvider? = { null }
        ) = SystemService(
            settingsRepository = SettingsRepositoryImpl(),
            settingsProvider = provider,
            cipher = cipher,
            dnsProviderFactory = dnsFactory,
            pushRuntimeSettings = pushRuntimeSettings
        )

        beforeTest {
            TestDatabase.initIfNeeded()
            TestDatabase.reset()
        }

        // ── Settings storage ─────────────────────────────────────────────────

        test("Settings reads new keys with defaults when the rows are absent") {
            val settings = Settings.from(SettingsRepositoryImpl().getAll())
            settings.dnsProvider shouldBe "none"
            settings.cfApiTokenSet shouldBe false
            settings.metricsPollIntervalSeconds shouldBe 5
            settings.metricsCollectionConcurrency shouldBe 8
            settings.agentReconcileIntervalSeconds shouldBe 30
        }

        test("cf_api_token is encrypted at rest and reported as set, never in plaintext") {
            val service = systemService()
            service.updateSettings(newUser(), PatchSettingsRequest(cfApiToken = "secret-token"))

            val stored = rawValue("cf_api_token")
            stored.shouldBeInstanceOf<String>()
            stored.shouldStartWith("enc:v1:")
            stored shouldNotContain "secret-token"
            EncryptedSettingValue.decrypt(cipher, stored) shouldBe "secret-token"

            service.getSettings().settings.cfApiTokenSet shouldBe true
        }

        test("a blank token leaves the stored value untouched") {
            val service = systemService()
            service.updateSettings(newUser(), PatchSettingsRequest(cfApiToken = "first"))
            val before = rawValue("cf_api_token")

            service.updateSettings(newUser(), PatchSettingsRequest(cfApiToken = "  "))
            rawValue("cf_api_token") shouldBe before
        }

        test("updateSettings rejects an unknown provider and out-of-range numerics") {
            val service = systemService()
            val userId = newUser()
            listOf(
                PatchSettingsRequest(dnsProvider = "route53"),
                PatchSettingsRequest(metricsPollIntervalSeconds = 0),
                PatchSettingsRequest(metricsPollIntervalSeconds = 3601),
                PatchSettingsRequest(metricsCollectionConcurrency = 0),
                PatchSettingsRequest(metricsCollectionConcurrency = 65),
                PatchSettingsRequest(agentReconcileIntervalSeconds = -1),
                PatchSettingsRequest(agentReconcileIntervalSeconds = 3601)
            ).forEach { req ->
                runCatching { service.updateSettings(userId, req) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<UnprocessableException>()
            }
        }

        test("the broadcast seam fires only when an agent-facing key changes") {
            var broadcasts = 0
            val service = systemService(pushRuntimeSettings = { broadcasts++ })
            val userId = newUser()

            service.updateSettings(userId, PatchSettingsRequest(metricsPollIntervalSeconds = 10))
            broadcasts shouldBe 1

            service.updateSettings(userId, PatchSettingsRequest(dnsZoneId = "z".repeat(32)))
            broadcasts shouldBe 1

            service.updateSettings(userId, PatchSettingsRequest(restartWindowSeconds = 900))
            broadcasts shouldBe 2
        }

        // ── DnsProviderResolver ──────────────────────────────────────────────

        test("resolver rebuilds on refresh and keeps the previous provider when the rebuild throws") {
            val provider = settingsProvider()
            var builds = 0
            val resolver = DnsProviderResolver(provider, cipher) { _ ->
                builds++
                throw IllegalStateException("bad token")
            }

            // First use builds — and fails, so there is no provider yet.
            resolver.current() shouldBe null
            builds shouldBe 1

            // A rebuild failure keeps whatever was cached before (still nothing here).
            resolver.refresh()
            builds shouldBe 2
            resolver.current() shouldBe null
        }

        test("resolver caches per (provider, token): a zone-only change does not rebuild") {
            val provider = settingsProvider()
            val seen = mutableListOf<DnsConfig>()
            val resolver = DnsProviderResolver(provider, cipher) { cfg ->
                seen.add(cfg)
                null
            }
            val userId = newUser()
            val service = systemService(provider)

            service.updateSettings(userId, PatchSettingsRequest(dnsProvider = "cloudflare", cfApiToken = "tok-1"))
            resolver.current()
            val buildsAfterFirst = seen.size

            service.updateSettings(userId, PatchSettingsRequest(dnsZoneId = "a".repeat(32)))
            resolver.current()

            buildsAfterFirst shouldBe 1
            seen.size shouldBe 1
        }

        test("resolver rebuilds when the token changes and decrypts it for the factory") {
            val provider = settingsProvider()
            val seen = mutableListOf<DnsConfig>()
            val resolver = DnsProviderResolver(provider, cipher) { cfg ->
                seen.add(cfg)
                null
            }
            val userId = newUser()
            val service = systemService(provider)

            service.updateSettings(userId, PatchSettingsRequest(dnsProvider = "cloudflare", cfApiToken = "tok-1"))
            resolver.current()
            service.updateSettings(userId, PatchSettingsRequest(cfApiToken = "tok-2"))
            resolver.current()

            seen.size shouldBe 2
            seen.first().cloudflareApiToken shouldBe "tok-1"
            seen.last().cloudflareApiToken shouldBe "tok-2"
        }

        // ── AgentRuntimeSettingsService ──────────────────────────────────────

        test("snapshot carries the five install-wide values from settings") {
            val provider = settingsProvider()
            systemService(provider).updateSettings(
                newUser(),
                PatchSettingsRequest(
                    metricsPollIntervalSeconds = 15,
                    metricsCollectionConcurrency = 4,
                    agentReconcileIntervalSeconds = 0,
                    restartMaxAttempts = 2,
                    jvmMetricsPollIntervalSeconds = 60
                )
            )
            val service = AgentRuntimeSettingsService(provider, NodeRepositoryImpl(), TestAgentGateway())

            val snapshot = service.snapshot()
            snapshot.metricsPollIntervalSeconds shouldBe 15
            snapshot.metricsCollectionConcurrency shouldBe 4
            snapshot.reconcileIntervalSeconds shouldBe 0
            snapshot.restartBudget.maxAttempts shouldBe 2
            snapshot.jvmMetricsPollIntervalSeconds shouldBe 60
        }

        test("pushAll pushes to every node and survives offline nodes") {
            val gateway = TestAgentGateway(sendResult = false)
            transaction {
                Nodes.insert {
                    it[hostname] = "n1"
                    it[displayName] = "n1"
                    it[publicIp] = "1.1.1.1"
                    it[privateIp] = "10.0.0.1"
                    it[tokenHash] = "h1"
                    it[status] = "ACTIVE"
                    it[health] = "HEALTHY"
                }
            }
            val service = AgentRuntimeSettingsService(settingsProvider(), NodeRepositoryImpl(), gateway)

            service.pushAll()

            gateway.sent.size shouldBe 1
            gateway.sent.single().second.hasAgentRuntimeSettings() shouldBe true
        }

        // ── gRPC register snapshot ───────────────────────────────────────────

        test("RegisterNode response carries the current runtime settings") {
            val service = createTestControlServiceImpl(
                nodeStateReconciler = NodeStateReconciler(NodeRepositoryImpl())
            )
            val request = registerNodeRequest {
                bootstrapToken = "test-token"
                metadata = nodeMetadata { hostname = "snap-node" }
            }

            val response = runBlocking { service.registerNode(request) }
            response.runtimeSettings.metricsPollIntervalSeconds shouldBe 5
            response.runtimeSettings.restartBudget.maxAttempts shouldBe 5
        }
    })
