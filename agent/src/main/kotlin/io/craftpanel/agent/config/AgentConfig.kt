package io.craftpanel.agent.config

import io.craftpanel.common.ContainerNames
import io.craftpanel.common.ServerPaths

data class AgentConfig(
    val profile: String,
    val masterAddress: String,
    val masterPort: Int,
    val masterHttpPort: Int,
    // Explicit path to master's CA cert PEM. Takes priority over the auto-fetched cert.
    val tlsCertPath: String,
    // Where the agent persists the CA cert received from master during registration.
    val caCertFilePath: String,
    val bootstrapToken: String,
    val keyFilePath: String,
    val dockerSocketPath: String,
    val dataBasePath: String,
    val hostDataBasePath: String,
    // Root for the human-readable `servers-by-name/<name>` symlink overlay.
    val serversByNameRoot: String,
    // Root for the human-readable `backups-by-server/<name>/<timestamp>.tar.gz` symlink overlay.
    val backupsByServerRoot: String,
    val mcRouterImage: String,
    val mcRouterUpdateOnStart: Boolean,
    val mcRouterContainerName: String,
    // When false, the agent never provisions, attaches, detaches, or metrics-queries mc-router.
    val mcRouterEnabled: Boolean = true,
    val publicIpUrl: String,
    val hostnameOverride: String,
    val systemReservedRamMb: Int,
    val systemReservedCpuMillicores: Int,
    val craftpanelNetwork: String,
    val containerNamePrefix: String,
    val privateIpOverride: String,
    // Explicit public IP reported to master. Takes priority over publicIpUrl discovery.
    val publicIpOverride: String = "",
    val metricsPollIntervalSeconds: Int,
    // Max concurrent per-container metrics collections. Bounds Docker-daemon load while removing
    // the serial O(N) scan that made stats lag on nodes with many servers.
    val metricsCollectionConcurrency: Int = 8,
    // Cadence of the convergence backstop sweep in the agent. 0 disables the sweep.
    val reconcileIntervalSeconds: Int = 30,
    // Max age (hours) a locally-cached image may be before a fresh pull is attempted.
    // Prod default 24h; tests set a very large value so local-only images are never re-pulled.
    val pullMaxImageAgeHours: Long = 24
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

        fun fromEnv(): AgentConfig {
            val dataPath = System.getenv("DATA_PATH") ?: "/data"
            return AgentConfig(
                profile = System.getenv("APP_PROFILE") ?: "prod",
                masterAddress = System.getenv("MASTER_HOST") ?: "localhost",
                masterPort = System.getenv("MASTER_GRPC_PORT")
                    ?.toIntOrNull() ?: 50051,
                masterHttpPort = System.getenv("MASTER_HTTP_PORT")
                    ?.toIntOrNull() ?: 8080,
                tlsCertPath = System.getenv("GRPC_TLS_CERT") ?: "",
                caCertFilePath = System.getenv("GRPC_CA_CERT_FILE") ?: "/app/config/grpc-ca.crt",
                bootstrapToken = secretFromFileOrEnv("NODE_BOOTSTRAP_TOKEN", "changeme"),
                keyFilePath = System.getenv("NODE_KEY_FILE") ?: "/app/config/node.key",
                dockerSocketPath = System.getenv("DOCKER_SOCKET") ?: "unix:///var/run/docker.sock",
                dataBasePath = dataPath,
                hostDataBasePath = System.getenv("HOST_DATA_PATH") ?: dataPath,
                serversByNameRoot = System.getenv("SERVERS_BY_NAME_PATH")
                    ?: ServerPaths.serversByNameRoot(dataPath),
                backupsByServerRoot = System.getenv("BACKUPS_BY_SERVER_PATH")
                    ?: ServerPaths.backupsByServerRoot(dataPath),
                mcRouterImage = System.getenv("MCROUTER_IMAGE") ?: "itzg/mc-router:latest",
                mcRouterUpdateOnStart = System.getenv("MCROUTER_UPDATE_ON_START")
                    ?.lowercase() != "false",
                mcRouterContainerName = System.getenv("MCROUTER_CONTAINER_NAME") ?: "",
                mcRouterEnabled = System.getenv("MCROUTER_ENABLED")
                    ?.lowercase() != "false",
                publicIpUrl = System.getenv("PUBLIC_IP_URL") ?: "",
                hostnameOverride = System.getenv("NODE_HOSTNAME") ?: "",
                systemReservedRamMb = System.getenv("SYSTEM_RESERVED_RAM_MB")
                    ?.toIntOrNull()
                    ?.coerceAtLeast(0) ?: 0,
                systemReservedCpuMillicores = System.getenv("SYSTEM_RESERVED_CPU_MILLICORES")
                    ?.toIntOrNull()
                    ?.coerceAtLeast(0) ?: 0,
                craftpanelNetwork = System.getenv("CRAFTPANEL_NETWORK") ?: "craftpanel",
                containerNamePrefix = System.getenv("CRAFTPANEL_CONTAINER_PREFIX") ?: ContainerNames.DEFAULT_PREFIX,
                privateIpOverride = System.getenv("NODE_PRIVATE_IP") ?: "",
                publicIpOverride = System.getenv("NODE_PUBLIC_IP") ?: "",
                metricsPollIntervalSeconds = System.getenv("METRICS_POLL_INTERVAL_SECONDS")
                    ?.toIntOrNull()
                    ?.coerceAtLeast(1) ?: 5,
                metricsCollectionConcurrency = System.getenv("METRICS_COLLECTION_CONCURRENCY")
                    ?.toIntOrNull()
                    ?.coerceAtLeast(1) ?: 8,
                reconcileIntervalSeconds = System.getenv("AGENT_RECONCILE_INTERVAL_SECONDS")
                    ?.toIntOrNull()
                    ?.coerceAtLeast(0) ?: 30,
                pullMaxImageAgeHours = System.getenv("PULL_MAX_IMAGE_AGE_HOURS")
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0) ?: 24
            )
        }
    }
}
