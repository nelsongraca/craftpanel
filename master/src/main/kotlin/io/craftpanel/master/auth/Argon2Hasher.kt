package io.craftpanel.master.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import java.util.*

object Argon2Hasher {

    private const val HASH_LENGTH = 32
    private const val SALT_LENGTH = 16
    private const val ITERATIONS = 3
    private const val MEMORY_KB = 65536
    private const val PARALLELISM = 4

    private val random = SecureRandom()

    // Pre-computed hash used when user is not found — ensures verify() always runs
    // to prevent timing-based user enumeration.
    val DUMMY_HASH: String by lazy { hash("craftpanel-dummy-verify-constant") }

    fun hash(password: String): String {
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val hash = argon2Hash(password.toCharArray(), salt)
        val encoder = Base64.getEncoder()
        return $$"$argon2id$v=19$m=$$MEMORY_KB,t=$$ITERATIONS,p=$$PARALLELISM$$${encoder.encodeToString(salt)}$$${encoder.encodeToString(hash)}"
    }

    fun verify(password: String, encoded: String): Boolean {
        return runCatching {
            val parts = encoded.split("$")
                .filter { it.isNotEmpty() }
            // parts: [argon2id, v=19, m=...,t=...,p=..., saltB64, hashB64]
            val saltB64 = parts[3]
            val hashB64 = parts[4]

            val decoder = Base64.getDecoder()
            val salt = decoder.decode(saltB64)
            val expectedHash = decoder.decode(hashB64)

            val actualHash = argon2Hash(password.toCharArray(), salt)
            actualHash.contentEquals(expectedHash)
        }.getOrDefault(false)
    }

    private fun argon2Hash(password: CharArray, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(ITERATIONS)
            .withMemoryAsKB(MEMORY_KB)
            .withParallelism(PARALLELISM)
            .withSalt(salt)
            .build()

        val generator = Argon2BytesGenerator()
        generator.init(params)

        val hash = ByteArray(HASH_LENGTH)
        generator.generateBytes(password, hash)
        return hash
    }
}
