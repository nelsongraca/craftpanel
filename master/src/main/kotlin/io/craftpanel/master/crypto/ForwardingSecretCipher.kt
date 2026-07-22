package io.craftpanel.master.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ForwardingSecretCipher(private val key: ByteArray) {

    init {
        require(key.size == 32) { "Forwarding key must be 32 bytes (256 bits), got ${key.size}" }
    }

    fun encrypt(plain: String): String {
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER)
        val spec = GCMParameterSpec(TAG_LEN_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        val ciphertext = cipher.doFinal(plain.encodeToByteArray())
        val combined = ByteArray(NONCE_LEN + ciphertext.size)
        System.arraycopy(nonce, 0, combined, 0, NONCE_LEN)
        System.arraycopy(ciphertext, 0, combined, NONCE_LEN, ciphertext.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    fun decrypt(stored: String): String {
        val combined = Base64.getDecoder().decode(stored)
        require(combined.size >= NONCE_LEN + TAG_LEN_MIN) {
            "Ciphertext too short: ${combined.size} bytes"
        }
        val nonce = combined.copyOfRange(0, NONCE_LEN)
        val ciphertext = combined.copyOfRange(NONCE_LEN, combined.size)
        val cipher = Cipher.getInstance(CIPHER)
        val spec = GCMParameterSpec(TAG_LEN_BITS, nonce)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        return cipher.doFinal(ciphertext).decodeToString()
    }

    companion object {
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val NONCE_LEN = 12
        private const val TAG_LEN_BITS = 128
        private const val TAG_LEN_MIN = 16
    }
}
