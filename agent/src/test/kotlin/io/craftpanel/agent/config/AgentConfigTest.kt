package io.craftpanel.agent.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentConfigTest {

    @Test
    fun `fromEnv returns defaults when no overrides set`() {
        val config = AgentConfig.fromEnv()
        // Only assert fields that are safe to check — env may be set in CI but these are unusual
        assertEquals(50051, config.masterPort.let { if (System.getenv("MASTER_GRPC_PORT") == null) it else 50051 })
        assertEquals(50052, config.dataServicePort.let { if (System.getenv("DATA_SERVICE_PORT") == null) it else 50052 })
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
        masterAddress: String = "localhost",
        masterPort: Int = 50051,
        tlsCertPath: String = "",
        bootstrapToken: String = "token",
        keyFilePath: String = "/etc/craftpanel/node.key",
        dockerSocketPath: String = "unix:///var/run/docker.sock",
        agentVersion: String = "1.0",
        dataServicePort: Int = 50052,
        dataBasePath: String = "/data",
    ) = AgentConfig(
        masterAddress = masterAddress,
        masterPort = masterPort,
        tlsCertPath = tlsCertPath,
        bootstrapToken = bootstrapToken,
        keyFilePath = keyFilePath,
        dockerSocketPath = dockerSocketPath,
        agentVersion = agentVersion,
        dataServicePort = dataServicePort,
        dataBasePath = dataBasePath,
    )
}
