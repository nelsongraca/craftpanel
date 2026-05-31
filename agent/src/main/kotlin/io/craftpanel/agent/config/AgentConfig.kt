package io.craftpanel.agent.config

data class AgentConfig(
    val profile: String,
    val masterAddress: String,
    val masterPort: Int,
    // Explicit path to master's CA cert PEM. Takes priority over the auto-fetched cert.
    val tlsCertPath: String,
    // Where the agent persists the CA cert received from master during registration.
    val caCertFilePath: String,
    val bootstrapToken: String,
    val keyFilePath: String,
    val dockerSocketPath: String,
    val agentVersion: String,
    val dataBasePath: String,
    val hostDataBasePath: String,
    val mcRouterImage: String,
    val mcRouterUpdateOnStart: Boolean,
    val publicIpUrl: String,
) {

    val tlsEnabled: Boolean get() = tlsCertPath.isNotBlank()
    val tlsConfigured: Boolean
        get() = tlsEnabled || java.io.File(caCertFilePath)
            .exists()

    fun validate() {
        if (profile == "dev") {
            if (bootstrapToken == "changeme") log.warn("NODE_BOOTSTRAP_TOKEN is default 'changeme' — change before production use")
            if (!tlsEnabled) log.warn("GRPC_TLS_CERT not set — running control channel in plaintext (dev only)")
            return
        }
        check(tlsConfigured) {
            "gRPC CA cert required outside dev profile — set GRPC_TLS_CERT or mount master's grpc-ca.crt at $caCertFilePath"
        }
        check(bootstrapToken != "changeme" && bootstrapToken.length >= 16) {
            "NODE_BOOTSTRAP_TOKEN must be set to a non-default value of at least 16 characters"
        }
    }

    companion object {

        private val log = org.slf4j.LoggerFactory.getLogger(AgentConfig::class.java)

        fun fromEnv(): AgentConfig = AgentConfig(
            profile = System.getenv("APP_PROFILE") ?: "prod",
            masterAddress = System.getenv("MASTER_HOST") ?: "localhost",
            masterPort = System.getenv("MASTER_GRPC_PORT")
                ?.toIntOrNull() ?: 50051,
            tlsCertPath = System.getenv("GRPC_TLS_CERT") ?: "",
            caCertFilePath = System.getenv("GRPC_CA_CERT_FILE") ?: "/etc/craftpanel/grpc-ca.crt",
            bootstrapToken = System.getenv("NODE_BOOTSTRAP_TOKEN") ?: "changeme",
            keyFilePath = System.getenv("NODE_KEY_FILE") ?: "/etc/craftpanel/node.key",
            dockerSocketPath = System.getenv("DOCKER_SOCKET") ?: "unix:///var/run/docker.sock",
            agentVersion = System.getenv("AGENT_VERSION") ?: "dev",
            dataBasePath = System.getenv("DATA_PATH") ?: "/data",
            hostDataBasePath = System.getenv("HOST_DATA_PATH")
                ?: System.getenv("DATA_PATH") ?: "/data",
            mcRouterImage = System.getenv("MCROUTER_IMAGE") ?: "itzg/mc-router:latest",
            mcRouterUpdateOnStart = System.getenv("MCROUTER_UPDATE_ON_START")
                ?.lowercase() != "false",
            publicIpUrl = System.getenv("PUBLIC_IP_URL") ?: "",
        )
    }
}
