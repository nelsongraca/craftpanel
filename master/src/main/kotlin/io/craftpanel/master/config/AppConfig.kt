package io.craftpanel.master.config

import io.ktor.server.config.*

data class DatabaseConfig(
    val url: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int,
)

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val expirySeconds: Long,
)

data class GrpcConfig(
    val port: Int,
    val tlsCertPath: String,
    val tlsKeyPath: String,
) {

    val tlsEnabled: Boolean get() = tlsCertPath.isNotBlank() && tlsKeyPath.isNotBlank()
}

data class NodeConfig(
    val bootstrapToken: String,
    val agentDataPort: Int,
    val agentTlsTrustCertPath: String = "",
) {

    val agentTlsEnabled: Boolean get() = agentTlsTrustCertPath.isNotBlank()
}

data class DnsConfig(
    val provider: String,
    val cloudflareApiToken: String,
)

data class CorsConfig(
    val allowedHosts: List<String>,
    val allowedSchemes: List<String>,
)

data class RateLimitConfig(
    val loginPerMinute: Int,
    val refreshPerMinute: Int,
)

class AppConfig(config: ApplicationConfig) {

    val profile: String = config.propertyOrNull("app.profile")
        ?.getString() ?: "prod"

    val database = DatabaseConfig(
        url = config.property("database.url")
            .getString(),
        username = config.property("database.username")
            .getString(),
        password = config.property("database.password")
            .getString(),
        maximumPoolSize = config.property("database.maximumPoolSize")
            .getString()
            .toInt(),
    )
    val jwt = JwtConfig(
        secret = config.property("jwt.secret")
            .getString(),
        issuer = config.property("jwt.issuer")
            .getString(),
        audience = config.property("jwt.audience")
            .getString(),
        expirySeconds = config.property("jwt.expirySeconds")
            .getString()
            .toLong(),
    )
    val grpc = GrpcConfig(
        port = config.property("grpc.port")
            .getString()
            .toInt(),
        tlsCertPath = config.property("grpc.tlsCertPath")
            .getString(),
        tlsKeyPath = config.property("grpc.tlsKeyPath")
            .getString(),
    )
    val node = NodeConfig(
        bootstrapToken = config.property("node.bootstrapToken")
            .getString(),
        agentDataPort = config.property("node.agentDataPort")
            .getString()
            .toIntOrNull() ?: 50052,
        agentTlsTrustCertPath = config.propertyOrNull("node.agentTlsTrustCertPath")
            ?.getString() ?: "",
    )
    val dns = DnsConfig(
        provider = config.propertyOrNull("dns.provider")
            ?.getString() ?: "none",
        cloudflareApiToken = config.propertyOrNull("dns.cloudflare.apiToken")
            ?.getString() ?: "",
    )
    val cors = CorsConfig(
        allowedHosts = config.propertyOrNull("cors.allowedHosts")
            ?.getString()
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() } ?: emptyList(),
        allowedSchemes = config.propertyOrNull("cors.allowedSchemes")
            ?.getString()
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() } ?: listOf("https"),
    )
    val rateLimit = RateLimitConfig(
        loginPerMinute = config.propertyOrNull("rateLimit.loginPerMinute")
            ?.getString()
            ?.toIntOrNull() ?: 10,
        refreshPerMinute = config.propertyOrNull("rateLimit.refreshPerMinute")
            ?.getString()
            ?.toIntOrNull() ?: 30,
    )

    fun validate() {
        if (profile == "dev") return
        val defaultJwtSecret = "changeme-at-least-32-characters-long"
        check(jwt.secret != defaultJwtSecret && jwt.secret.length >= 32) {
            "JWT_SECRET must be set to a non-default value of at least 32 characters"
        }
        check(node.bootstrapToken != "changeme" && node.bootstrapToken.length >= 16) {
            "NODE_BOOTSTRAP_TOKEN must be set to a non-default value of at least 16 characters"
        }
        check(grpc.tlsEnabled) {
            "gRPC TLS is required outside dev profile — set GRPC_TLS_CERT and GRPC_TLS_KEY"
        }
        check(node.agentTlsEnabled) {
            "Agent TLS trust cert is required outside dev profile — set NODE_AGENT_TLS_TRUST_CERT"
        }
    }
}
