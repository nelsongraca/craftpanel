package io.craftpanel.agent.config

data class AgentConfig(
    val profile: String,
    val masterAddress: String,
    val masterPort: Int,
    val tlsCertPath: String,
    val bootstrapToken: String,
    val keyFilePath: String,
    val dataTokenFilePath: String,
    val dockerSocketPath: String,
    val agentVersion: String,
    val dataServicePort: Int,
    val dataServiceTlsCertPath: String,
    val dataServiceTlsKeyPath: String,
    val dataBasePath: String,
    val mcRouterImage: String,
    val mcRouterUpdateOnStart: Boolean,
    val publicIpUrl: String,
) {

    val tlsEnabled: Boolean get() = tlsCertPath.isNotBlank()
    val dataServiceTlsEnabled: Boolean get() = dataServiceTlsCertPath.isNotBlank() && dataServiceTlsKeyPath.isNotBlank()

    fun validate() {
        if (profile == "dev") {
            if (bootstrapToken == "changeme") log.warn("NODE_BOOTSTRAP_TOKEN is default 'changeme' — change before production use")
            if (!tlsEnabled) log.warn("GRPC_TLS_CERT not set — running control channel in plaintext (dev only)")
            if (!dataServiceTlsEnabled) log.warn("DATA_SERVICE_TLS_CERT/KEY not set — DataService running in plaintext (dev only)")
            return
        }
        check(tlsEnabled) { "GRPC_TLS_CERT is required outside dev profile" }
        check(dataServiceTlsEnabled) { "DATA_SERVICE_TLS_CERT and DATA_SERVICE_TLS_KEY are required outside dev profile" }
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
            bootstrapToken = System.getenv("NODE_BOOTSTRAP_TOKEN") ?: "changeme",
            keyFilePath = System.getenv("NODE_KEY_FILE") ?: "/etc/craftpanel/node.key",
            dataTokenFilePath = System.getenv("NODE_DATA_TOKEN_FILE") ?: "/etc/craftpanel/node.data-token",
            dockerSocketPath = System.getenv("DOCKER_SOCKET") ?: "unix:///var/run/docker.sock",
            agentVersion = System.getenv("AGENT_VERSION") ?: "dev",
            dataServicePort = System.getenv("DATA_SERVICE_PORT")
                ?.toIntOrNull() ?: 50052,
            dataServiceTlsCertPath = System.getenv("DATA_SERVICE_TLS_CERT") ?: "",
            dataServiceTlsKeyPath = System.getenv("DATA_SERVICE_TLS_KEY") ?: "",
            dataBasePath = System.getenv("DATA_PATH") ?: "/data",
            mcRouterImage = System.getenv("MCROUTER_IMAGE") ?: "itzg/mc-router:latest",
            mcRouterUpdateOnStart = System.getenv("MCROUTER_UPDATE_ON_START")
                ?.lowercase() != "false",
            publicIpUrl = System.getenv("PUBLIC_IP_URL") ?: "",
        )
    }
}
