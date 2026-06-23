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
    // Directory where auto-generated CA and server certs are persisted.
    // Ignored when tlsCertPath/tlsKeyPath are set explicitly.
    val certStorePath: String,
    val tlsSans: List<String>,
) {

    // Explicit cert paths take priority over auto-gen
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

data class AuthConfig(
    val secureCookies: Boolean,
)

data class RateLimitConfig(
    val loginPerMinute: Int,
    val refreshPerMinute: Int,
)

data class ImagesConfig(
    val minecraftImage: String,
    val proxyImage: String,
) {
    fun deriveImage(serverType: String, tag: String): String {
        val base = when (serverType) {
            "BUNGEECORD", "VELOCITY", "WATERFALL" -> proxyImage
            else -> minecraftImage
        }
        return if (':' in base) base else "$base:$tag"
    }
}

data class RestartConfig(
    val maxAttempts: Int,
    val windowSeconds: Long,
)

data class AdminSeedConfig(
    val email: String,
    val password: String,
    val username: String,
) {

    val enabled: Boolean get() = email.isNotBlank() && password.isNotBlank()
}

class AppConfig(config: ApplicationConfig) {

    val profile: String = config.propertyOrNull("app.profile")
        ?.getString() ?: "prod"

    val database = DatabaseConfig(
        url = config.property("database.url")
            .getString(),
        username = config.property("database.username")
            .getString(),
        password = secretFromFileOrValue(
            "DATABASE_PASSWORD",
            config.property("database.password").getString(),
        ),
        maximumPoolSize = config.property("database.maximumPoolSize")
            .getString()
            .toInt(),
    )
    val jwt = JwtConfig(
        secret = secretFromFileOrValue(
            "JWT_SECRET",
            config.property("jwt.secret").getString(),
        ),
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
        tlsCertPath = config.propertyOrNull("grpc.tlsCertPath")
            ?.getString() ?: "",
        tlsKeyPath = config.propertyOrNull("grpc.tlsKeyPath")
            ?.getString() ?: "",
        certStorePath = config.propertyOrNull("grpc.certStorePath")
            ?.getString() ?: "/etc/craftpanel/certs",
        tlsSans = config.propertyOrNull("grpc.tlsSans")
            ?.getString()
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() } ?: emptyList(),
    )
    val node = NodeConfig(
        bootstrapToken = secretFromFileOrValue(
            "NODE_BOOTSTRAP_TOKEN",
            config.property("node.bootstrapToken").getString(),
        ),
        agentDataPort = config.property("node.agentDataPort")
            .getString()
            .toIntOrNull() ?: 50052,
        agentTlsTrustCertPath = config.propertyOrNull("node.agentTlsTrustCertPath")
            ?.getString() ?: "",
    )
    val dns = DnsConfig(
        provider = config.propertyOrNull("dns.provider")
            ?.getString() ?: "none",
        cloudflareApiToken = secretFromFileOrValue(
            "CF_API_TOKEN",
            config.propertyOrNull("dns.cloudflare.apiToken")?.getString() ?: "",
        ),
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
    val auth = AuthConfig(
        secureCookies = config.propertyOrNull("auth.secureCookies")
            ?.getString()
            ?.toBooleanStrictOrNull() ?: true,
    )
    val rateLimit = RateLimitConfig(
        loginPerMinute = config.propertyOrNull("rateLimit.loginPerMinute")
            ?.getString()
            ?.toIntOrNull() ?: 10,
        refreshPerMinute = config.propertyOrNull("rateLimit.refreshPerMinute")
            ?.getString()
            ?.toIntOrNull() ?: 30,
    )
    val adminSeed = AdminSeedConfig(
        email = config.propertyOrNull("adminSeed.email")
            ?.getString() ?: "",
        password = config.propertyOrNull("adminSeed.password")
            ?.getString() ?: "",
        username = config.propertyOrNull("adminSeed.username")
            ?.getString() ?: "admin",
    )
    val images = ImagesConfig(
        minecraftImage = config.propertyOrNull("images.minecraftImage")
            ?.getString() ?: "itzg/minecraft-server",
        proxyImage = config.propertyOrNull("images.proxyImage")
            ?.getString() ?: "itzg/mc-proxy",
    )
    val restart = RestartConfig(
        maxAttempts = config.propertyOrNull("restart.maxAttempts")
            ?.getString()
            ?.toIntOrNull()
            ?.coerceAtLeast(0) ?: 5,
        windowSeconds = config.propertyOrNull("restart.windowSeconds")
            ?.getString()
            ?.toLongOrNull()
            ?.coerceAtLeast(1) ?: 600,
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
        // TLS is always enforced: either via explicit cert paths or auto-generated certs.
        // The actual TLS enforcement happens in GrpcServer.start().
    }
}
