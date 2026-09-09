package io.craftpanel.master.auth

import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.DefaultCodeVerifier
import dev.samstevens.totp.code.HashingAlgorithm
import dev.samstevens.totp.qr.QrDataFactory
import dev.samstevens.totp.qr.ZxingPngQrGenerator
import dev.samstevens.totp.secret.DefaultSecretGenerator
import dev.samstevens.totp.time.SystemTimeProvider
import io.craftpanel.master.crypto.SecretCipher
import io.craftpanel.master.util.CryptoUtils
import java.security.SecureRandom
import java.util.Base64

class TotpService(private val cipher: SecretCipher) {

    private val secretGenerator = DefaultSecretGenerator(SECRET_BYTES)
    private val random = SecureRandom()

    private val codeVerifier = DefaultCodeVerifier(DefaultCodeGenerator(), SystemTimeProvider()).apply {
        setAllowedTimePeriodDiscrepancy(1)
    }

    fun generateSecret(): String = secretGenerator.generate()

    fun createQrCodeDataUri(secret: String, email: String): String {
        val data = QrDataFactory(HashingAlgorithm.SHA1, DIGITS, PERIOD)
            .newBuilder()
            .label(email)
            .secret(secret)
            .issuer(ISSUER)
            .build()
        val generator = ZxingPngQrGenerator()
        generator.setImageSize(QR_SIZE)
        val image = generator.generate(data)
        return "data:${generator.imageMimeType};base64," + Base64.getEncoder().encodeToString(image)
    }

    fun validate(secret: String, code: String): Boolean =
        code.length == DIGITS &&
            code.all { it.isDigit() } &&
            codeVerifier.isValidCode(secret, code)

    fun generateRecoveryCodes(count: Int = RECOVERY_CODE_COUNT): List<String> =
        List(count) { "%08d".format(random.nextInt(RECOVERY_CODE_MAX)) }

    fun hashRecoveryCode(code: String): String = CryptoUtils.sha256Hex(code)

    fun encryptSecret(plain: String): String = cipher.encrypt(plain)

    fun decryptSecret(stored: String): String = cipher.decrypt(stored)

    companion object {
        private const val ISSUER = "CraftPanel"
        private const val SECRET_BYTES = 20
        private const val DIGITS = 6
        private const val PERIOD = 30
        private const val QR_SIZE = 220
        private const val RECOVERY_CODE_COUNT = 10
        private const val RECOVERY_CODE_MAX = 100_000_000
    }
}