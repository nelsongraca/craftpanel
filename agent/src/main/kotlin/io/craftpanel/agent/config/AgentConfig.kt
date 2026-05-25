package io.craftpanel.agent.config

import java.nio.file.Paths

data class AgentConfig(
    val masterAddress: String,
    val masterPort: Int,
    val tlsCertPath: String,
    val bootstrapToken: String,
    val keyFilePath: String,
    val dockerSocketPath: String,
    val agentVersion: String,
) {
    val tlsEnabled: Boolean get() = tlsCertPath.isNotBlank()

    companion object {
        fun fromEnv(): AgentConfig = AgentConfig(
            masterAddress = System.getenv("MASTER_HOST") ?: "localhost",
            masterPort = System.getenv("MASTER_GRPC_PORT")?.toIntOrNull() ?: 50051,
            tlsCertPath = System.getenv("GRPC_TLS_CERT") ?: "",
            bootstrapToken = System.getenv("NODE_BOOTSTRAP_TOKEN") ?: "changeme",
            keyFilePath = System.getenv("NODE_KEY_FILE") ?: "/etc/craftpanel/node.key",
            dockerSocketPath = System.getenv("DOCKER_SOCKET") ?: "unix:///var/run/docker.sock",
            agentVersion = System.getenv("AGENT_VERSION") ?: "dev",
        )
    }
}
