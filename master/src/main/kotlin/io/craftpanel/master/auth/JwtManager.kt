package io.craftpanel.master.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import io.craftpanel.master.config.JwtConfig
import java.util.Date
import java.util.UUID

data class TokenClaims(
    val userId: UUID,
    val name: String,
    val email: String,
    val groups: List<String>,
)

class JwtManager(private val config: JwtConfig) {
    val expirySeconds: Long get() = config.expirySeconds

    private val algorithm = Algorithm.HMAC256(config.secret)

    val verifier = JWT.require(algorithm)
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .build()

    fun generate(claims: TokenClaims): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(claims.userId.toString())
            .withClaim("name", claims.name)
            .withClaim("email", claims.email)
            .withClaim("groups", claims.groups)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + config.expirySeconds * 1000))
            .sign(algorithm)
    }

    fun decode(token: String): DecodedJWT = verifier.verify(token)
}
