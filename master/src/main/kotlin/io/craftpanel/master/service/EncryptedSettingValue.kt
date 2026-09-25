package io.craftpanel.master.service

import io.craftpanel.master.crypto.SecretCipher

/**
 * Encodes/decodes a `system_settings.value` that holds an encrypted secret. Kept separate from
 * [SecretCipher] so the TOTP and forwarding-secret storage formats are untouched; only settings
 * written through this helper carry the [PREFIX] marker.
 *
 * The marker makes a stored value self-describing, so a future migration can tell ciphertext from a
 * plaintext value written by an older version, and [Settings.from] can report
 * `cf_api_token_set` by a plain non-blank check without decrypting anything.
 */
object EncryptedSettingValue {

    private const val PREFIX = "enc:v1:"

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    fun encrypt(cipher: SecretCipher, plain: String): String = PREFIX + cipher.encrypt(plain)

    /** Decrypts a stored value; a value without the marker is returned as-is (legacy plaintext). */
    fun decrypt(cipher: SecretCipher, stored: String): String =
        if (isEncrypted(stored)) cipher.decrypt(stored.removePrefix(PREFIX)) else stored
}
