package io.craftpanel.master.crypto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import javax.crypto.AEADBadTagException

class ForwardingSecretCipherTest :
    FunSpec({

        test("round-trip encrypt and decrypt") {
            val key = ByteArray(32) { 0x42 }
            val cipher = ForwardingSecretCipher(key)
            val plain = "my-secret-value-12345"
            val stored = cipher.encrypt(plain)
            stored shouldNotBe plain
            stored shouldNotBe ""
            cipher.decrypt(stored) shouldBe plain
        }

        test("produces different ciphertext each time (random nonce)") {
            val key = ByteArray(32) { 0x42 }
            val cipher = ForwardingSecretCipher(key)
            val plain = "same-plaintext"
            val a = cipher.encrypt(plain)
            val b = cipher.encrypt(plain)
            a shouldNotBe b
            cipher.decrypt(a) shouldBe plain
            cipher.decrypt(b) shouldBe plain
        }

        test("tampered ciphertext throws AEADBadTagException") {
            val key = ByteArray(32) { 0x42 }
            val cipher = ForwardingSecretCipher(key)
            val stored = cipher.encrypt("secret")
            val tampered = stored.take(stored.length - 2) + "XX"
            shouldThrow<AEADBadTagException> { cipher.decrypt(tampered) }
        }

        test("wrong key fails to decrypt") {
            val keyA = ByteArray(32) { 0x42 }
            val keyB = ByteArray(32) { 0x00 }
            val cipherA = ForwardingSecretCipher(keyA)
            val cipherB = ForwardingSecretCipher(keyB)
            val stored = cipherA.encrypt("secret")
            shouldThrow<AEADBadTagException> { cipherB.decrypt(stored) }
        }

        test("rejects wrong key length") {
            shouldThrow<IllegalArgumentException> { ForwardingSecretCipher(ByteArray(16)) }
            shouldThrow<IllegalArgumentException> { ForwardingSecretCipher(ByteArray(0)) }
        }

        test("accepts 32-byte key") {
            ForwardingSecretCipher(ByteArray(32))
        }

        test("encrypts empty string") {
            val cipher = ForwardingSecretCipher(ByteArray(32) { 0x42 })
            val stored = cipher.encrypt("")
            stored shouldNotBe ""
            cipher.decrypt(stored) shouldBe ""
        }
    })
