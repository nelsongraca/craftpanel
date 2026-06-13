package io.craftpanel.master.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.craftpanel.master.config.JwtConfig
import kotlin.uuid.Uuid
import java.time.Instant

const val JWT_AUTH = "auth-jwt"

data class TokenClaims(
    val userId: Uuid,
    val name: String,
    val email: String,
    val groups: List<String>,
)

class JwtManager(private val config: JwtConfig) {

    val expirySeconds: Long get() = config.expirySeconds

    private val algorithm = Algorithm.HMAC256(config.secret)

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .withClaimPresence("sub")
        .build()

    fun generate(claims: TokenClaims): String {
        val now = Instant.now()
        return JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(claims.userId.toString())
            .withClaim("name", claims.name)
            .withClaim("email", claims.email)
            .withClaim("groups", claims.groups)
            .withIssuedAt(now)
            .withExpiresAt(now.plusSeconds(config.expirySeconds))
            .sign(algorithm)
    }

}
