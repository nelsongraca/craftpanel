package io.craftpanel.master.config

import io.ktor.server.config.ApplicationConfig

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
)

class AppConfig(config: ApplicationConfig) {
    val database = DatabaseConfig(
        url = config.property("database.url").getString(),
        username = config.property("database.username").getString(),
        password = config.property("database.password").getString(),
        maximumPoolSize = config.property("database.maximumPoolSize").getString().toInt(),
    )
    val jwt = JwtConfig(
        secret = config.property("jwt.secret").getString(),
        issuer = config.property("jwt.issuer").getString(),
        audience = config.property("jwt.audience").getString(),
        expirySeconds = config.property("jwt.expirySeconds").getString().toLong(),
    )
    val grpc = GrpcConfig(
        port = config.property("grpc.port").getString().toInt(),
        tlsCertPath = config.property("grpc.tlsCertPath").getString(),
        tlsKeyPath = config.property("grpc.tlsKeyPath").getString(),
    )
    val node = NodeConfig(
        bootstrapToken = config.property("node.bootstrapToken").getString(),
    )
}
