package com.samacharam.core.sync

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

actual object SyncCrypto {
    private val random = SecureRandom()

    actual fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    actual fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    actual fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "Sync keys are 256-bit." }
        val nonce = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    actual fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        if (key.size != 32 || sealed.size < 12 + 16) throw SyncException("Encrypted data is incomplete.")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, 12, sealed.size - 12)
        } catch (e: GeneralSecurityException) {
            throw SyncException("Could not decrypt: wrong key or passphrase, or the data was changed.")
        }
    }

    actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}
