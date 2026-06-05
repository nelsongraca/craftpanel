package io.craftpanel.agent.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentConfigTest {

    @Test
    fun `fromEnv returns defaults when no overrides set`() {
        val config = AgentConfig.fromEnv()
        assertEquals(50051, config.masterPort.let { if (System.getenv("MASTER_GRPC_PORT") == null) it else 50051 })
    }

    @Test
    fun `tlsEnabled is true when tlsCertPath is non-blank`() {
        val config = config(tlsCertPath = "/etc/certs/ca.pem")
        assertTrue(config.tlsEnabled)
    }

    @Test
    fun `tlsEnabled is false when tlsCertPath is blank`() {
        val config = config(tlsCertPath = "")
        assertFalse(config.tlsEnabled)
    }

    @Test
    fun `tlsEnabled is false when tlsCertPath is whitespace`() {
        val config = config(tlsCertPath = "   ")
        assertFalse(config.tlsEnabled)
    }

    @Test
    fun `validate throws in prod when bootstrapToken is changeme`() {
        val config = config(profile = "prod", bootstrapToken = "changeme")
        val ex = runCatching { config.validate() }.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
    }

    @Test
    fun `validate throws in prod when TLS paths missing`() {
        val config = config(profile = "prod", bootstrapToken = "valid-token-at-least-16", tlsCertPath = "")
        val ex = runCatching { config.validate() }.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
    }

    @Test
    fun `validate passes in dev profile regardless of tls`() {
        val config = config(profile = "dev", tlsCertPath = "", bootstrapToken = "changeme")
        config.validate() // must not throw
    }

    @Test
    fun `hostnameOverride defaults to empty string`() {
        val config = config()
        assertEquals("", config.hostnameOverride)
    }

    @Test
    fun `hostnameOverride reflects configured value`() {
        val config = config(hostnameOverride = "my-node.example.com")
        assertEquals("my-node.example.com", config.hostnameOverride)
    }

    @Test
    fun `data class equality holds for identical configs`() {
        val a = config()
        val b = config()
        assertEquals(a, b)
    }

    @Test
    fun `copy preserves all fields`() {
        val original = config(masterAddress = "master.example.com", masterPort = 9090)
        val copy = original.copy(masterPort = 9091)
        assertEquals("master.example.com", copy.masterAddress)
        assertEquals(9091, copy.masterPort)
        assertEquals(original.tlsCertPath, copy.tlsCertPath)
    }

    private fun config(
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
        mcRouterImage: String = "itzg/mc-router:latest",
        mcRouterUpdateOnStart: Boolean = true,
        publicIpUrl: String = "",
        hostnameOverride: String = "",
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
        mcRouterImage = mcRouterImage,
        mcRouterUpdateOnStart = mcRouterUpdateOnStart,
        publicIpUrl = publicIpUrl,
        hostnameOverride = hostnameOverride,
        systemReservedRamMb = 0,
    )
}
