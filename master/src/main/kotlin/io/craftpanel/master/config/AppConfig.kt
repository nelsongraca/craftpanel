package io.craftpanel.master.config

import io.craftpanel.master.domain.ServerType
import io.ktor.server.config.*

data class DatabaseConfig(val url: String, val username: String, val password: String, val maximumPoolSize: Int)

data class JwtConfig(val secret: String, val issuer: String, val audience: String, val expirySeconds: Long)

data class GrpcConfig(
    val port: Int,
    val tlsCertPath: String,
    val tlsKeyPath: String,
    // Directory where auto-generated CA and server certs are persisted.
    // Ignored when tlsCertPath/tlsKeyPath are set explicitly.
    val certStorePath: String,
    val tlsSans: List<String>
) {

    // Explicit cert paths take priority over auto-gen
    val tlsEnabled: Boolean get() = tlsCertPath.isNotBlank() && tlsKeyPath.isNotBlank()
}

data class NodeConfig(val bootstrapToken: String, val agentDataPort: Int, val agentTlsTrustCertPath: String = "") {

    val agentTlsEnabled: Boolean get() = agentTlsTrustCertPath.isNotBlank()
}

data class DnsConfig(val provider: String, val cloudflareApiToken: String)

data class CorsOrigin(val host: String, val scheme: String)

data class CorsConfig(val origins: List<CorsOrigin>)

data class AuthConfig(val secureCookies: Boolean, val cookieDomain: String = "")

data class RateLimitConfig(val loginPerMinute: Int, val refreshPerMinute: Int)

data class ImagesConfig(val minecraftImage: String, val proxyImage: String) {

    fun deriveImage(serverType: ServerType, tag: String): String {
        val base = if (serverType.isProxy) proxyImage else minecraftImage
        return if (':' in base) base else "$base:$tag"
    }

    // Container working directory the persistent data volume binds to. itzg/minecraft-server
    // uses "/data"; the itzg/mc-proxy family (Velocity/BungeeCord/Waterfall) uses "/server".
    fun dataContainerPath(serverType: ServerType): String = if (serverType.isProxy) "/server" else "/data"

    // Internal port the server process binds inside the container. itzg/minecraft-server
    // listens on 25565; the itzg/mc-proxy family (Velocity/BungeeCord/Waterfall) on 25577.
    // Must match what the exposed port and mc-router label point at, or proxy traffic and
    // health checks hit the wrong internal port (issue #30).
    fun internalListenPort(serverType: ServerType): Int = if (serverType.isProxy) 25577 else 25565
}

data class AdminSeedConfig(val email: String, val password: String, val username: String) {

    val enabled: Boolean get() = email.isNotBlank() && password.isNotBlank()
}

data class DockerConfig(val endpoint: String = "")

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
            config.property("database.password")
                .getString()
        ),
        maximumPoolSize = config.property("database.maximumPoolSize")
            .getString()
            .toInt()
    )
    val jwt = JwtConfig(
        secret = secretFromFileOrValue(
            "JWT_SECRET",
            config.property("jwt.secret")
                .getString()
        ),
        issuer = config.property("jwt.issuer")
            .getString(),
        audience = config.property("jwt.audience")
            .getString(),
        expirySeconds = config.property("jwt.expirySeconds")
            .getString()
            .toLong()
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
            ?.filter { it.isNotEmpty() } ?: emptyList()
    )
    val node = NodeConfig(
        bootstrapToken = secretFromFileOrValue(
            "NODE_BOOTSTRAP_TOKEN",
            config.property("node.bootstrapToken")
                .getString()
        ),
        agentDataPort = config.property("node.agentDataPort")
            .getString()
            .toIntOrNull() ?: 50052,
        agentTlsTrustCertPath = config.propertyOrNull("node.agentTlsTrustCertPath")
            ?.getString() ?: ""
    )
    val dns = DnsConfig(
        provider = config.propertyOrNull("dns.provider")
            ?.getString() ?: "none",
        cloudflareApiToken = secretFromFileOrValue(
            "CF_API_TOKEN",
            config.propertyOrNull("dns.cloudflare.apiToken")
                ?.getString() ?: ""
        )
    )
    val cors = CorsConfig(
        origins = config.propertyOrNull("cors.publicUrls")
            ?.getString()
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { url ->
                val uri = java.net.URI(url)
                val host = requireNotNull(uri.host) { "PUBLIC_URLS entry '$url' must be a full URL, e.g. https://example.com" }
                val scheme = requireNotNull(uri.scheme) { "PUBLIC_URLS entry '$url' must include a scheme, e.g. https://example.com" }
                CorsOrigin(host = host, scheme = scheme)
            } ?: emptyList()
    )
    val auth = AuthConfig(
        secureCookies = config.propertyOrNull("auth.secureCookies")
            ?.getString()
            ?.toBooleanStrictOrNull() ?: true,
        cookieDomain = config.propertyOrNull("auth.cookieDomain")
            ?.getString() ?: ""
    )
    val rateLimit = RateLimitConfig(
        loginPerMinute = config.propertyOrNull("rateLimit.loginPerMinute")
            ?.getString()
            ?.toIntOrNull() ?: 10,
        refreshPerMinute = config.propertyOrNull("rateLimit.refreshPerMinute")
            ?.getString()
            ?.toIntOrNull() ?: 30
    )
    val adminSeed = AdminSeedConfig(
        email = config.propertyOrNull("adminSeed.email")
            ?.getString() ?: "",
        password = config.propertyOrNull("adminSeed.password")
            ?.getString() ?: "",
        username = config.propertyOrNull("adminSeed.username")
            ?.getString() ?: "admin"
    )
    val images = ImagesConfig(
        minecraftImage = config.propertyOrNull("images.minecraftImage")
            ?.getString() ?: "itzg/minecraft-server",
        proxyImage = config.propertyOrNull("images.proxyImage")
            ?.getString() ?: "itzg/mc-proxy"
    )
    val docker = DockerConfig(
        endpoint = config.propertyOrNull("docker.endpoint")
            ?.getString() ?: ""
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
        check(cors.origins.isNotEmpty()) {
            "PUBLIC_URLS must be set outside app.profile=dev, or the API will reject every browser request with CORS 403"
        }
        // TLS is always enforced: either via explicit cert paths or auto-generated certs.
        // The actual TLS enforcement happens in GrpcServer.start().
    }
}
