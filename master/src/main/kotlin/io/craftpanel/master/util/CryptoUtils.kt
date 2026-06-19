package io.craftpanel.master.util

import java.security.SecureRandom
import java.util.Base64

object CryptoUtils {
    private val random = SecureRandom()

    fun generateToken(bytes: Int): String {
        val buf = ByteArray(bytes).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }
}
