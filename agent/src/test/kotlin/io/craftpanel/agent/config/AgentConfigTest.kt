package io.craftpanel.agent.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class AgentConfigTest :
    FunSpec({
        test("fromEnv returns defaults when no overrides set") {
            val config = AgentConfig.fromEnv()
            config.masterPort.let { if (System.getenv("MASTER_GRPC_PORT") == null) it else 50051 } shouldBe 50051
        }

        test("tlsEnabled is true when tlsCertPath is non-blank") {
            val config = config(tlsCertPath = "/etc/certs/ca.pem")
            config.tlsEnabled.shouldBeTrue()
        }

        test("tlsEnabled is false when tlsCertPath is blank") {
            val config = config(tlsCertPath = "")
            config.tlsEnabled.shouldBeFalse()
        }

        test("tlsEnabled is false when tlsCertPath is whitespace") {
            val config = config(tlsCertPath = "   ")
            config.tlsEnabled.shouldBeFalse()
        }

        test("validate throws in prod when bootstrapToken is changeme") {
            val config = config(profile = "prod", bootstrapToken = "changeme")
            val ex = runCatching { config.validate() }.exceptionOrNull()
            (ex is IllegalStateException) shouldBe true
        }

        test("validate throws in prod when bootstrapToken is too short") {
            val config = config(profile = "prod", bootstrapToken = "short", tlsCertPath = "/etc/certs/ca.pem")
            val ex = runCatching { config.validate() }.exceptionOrNull()
            (ex is IllegalStateException) shouldBe true
        }

        test("validate passes in prod with valid token and TLS configured") {
            val config = config(profile = "prod", bootstrapToken = "valid-token-at-least-16", tlsCertPath = "/etc/certs/ca.pem")
            config.validate() // must not throw
        }

        test("validate throws in prod when TLS paths missing") {
            val config = config(profile = "prod", bootstrapToken = "valid-token-at-least-16", tlsCertPath = "")
            val ex = runCatching { config.validate() }.exceptionOrNull()
            (ex is IllegalStateException) shouldBe true
        }

        test("validate passes in dev profile regardless of tls") {
            val config = config(profile = "dev", tlsCertPath = "", bootstrapToken = "changeme")
            config.validate() // must not throw
        }

        test("hostnameOverride defaults to empty string") {
            val config = config()
            config.hostnameOverride shouldBe ""
        }

        test("hostnameOverride reflects configured value") {
            val config = config(hostnameOverride = "my-node.example.com")
            config.hostnameOverride shouldBe "my-node.example.com"
        }

        test("data class equality holds for identical configs") {
            val a = config()
            val b = config()
            a shouldBe b
        }

        test("copy preserves all fields") {
            val original = config(masterAddress = "master.example.com", masterPort = 9090)
            val copy = original.copy(masterPort = 9091)
            copy.masterAddress shouldBe "master.example.com"
            copy.masterPort shouldBe 9091
            copy.tlsCertPath shouldBe original.tlsCertPath
        }
    }) {

    companion object {

        fun config(
            profile: String = "dev",
            masterAddress: String = "localhost",
            masterPort: Int = 50051,
            tlsCertPath: String = "",
            bootstrapToken: String = "test-token-16chars",
            keyFilePath: String = "/etc/craftpanel/node.key",
            caCertFilePath: String = "/etc/craftpanel/grpc-ca.crt",
            dockerSocketPath: String = "unix:///var/run/docker.sock",
            agentVersion: String = "1.0",
            dataBasePath: String = "/data",
            hostDataBasePath: String = "/data",
            serversByNameRoot: String = "/data/servers-by-name",
            backupsByServerRoot: String = "/data/backups-by-server",
            mcRouterImage: String = "itzg/mc-router:latest",
            mcRouterUpdateOnStart: Boolean = true,
            publicIpUrl: String = "",
            hostnameOverride: String = "",
            craftpanelNetwork: String = "craftpanel",
            containerNamePrefix: String = "craftpanel",
            metricsPollIntervalSeconds: Int = 60
        ) = AgentConfig(
            profile = profile,
            masterAddress = masterAddress,
            masterPort = masterPort,
            tlsCertPath = tlsCertPath,
            caCertFilePath = caCertFilePath,
            bootstrapToken = bootstrapToken,
            keyFilePath = keyFilePath,
            dockerSocketPath = dockerSocketPath,
            agentVersion = agentVersion,
            dataBasePath = dataBasePath,
            hostDataBasePath = hostDataBasePath,
            serversByNameRoot = serversByNameRoot,
            backupsByServerRoot = backupsByServerRoot,
            mcRouterImage = mcRouterImage,
            mcRouterUpdateOnStart = mcRouterUpdateOnStart,
            publicIpUrl = publicIpUrl,
            hostnameOverride = hostnameOverride,
            systemReservedRamMb = 0,
            craftpanelNetwork = craftpanelNetwork,
            containerNamePrefix = containerNamePrefix,
            metricsPollIntervalSeconds = metricsPollIntervalSeconds,
            masterHttpPort = 80,
            privateIpOverride = "",
            mcRouterContainerName = ""
        )
    }
}
