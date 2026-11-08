package com.samacharam.core.sync

/** Platform cryptography for sync. Implemented with javax.crypto on the JVM and Android. */
expect object SyncCrypto {
    fun randomBytes(count: Int): ByteArray

    /** PBKDF2-HMAC-SHA256, 256-bit output. */
    fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray

    /** AES-256-GCM. Output is a 12-byte nonce followed by ciphertext and tag. [aad] is authenticated, not encrypted. */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

    /** Throws [SyncException] if the key is wrong or the data or [aad] was altered. */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray

    fun sha256(data: ByteArray): ByteArray
}

class SyncException(message: String) : Exception(message)

fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

fun String.hexToBytes(): ByteArray {
    if (length % 2 != 0 || any { it !in "0123456789abcdef" }) throw SyncException("Invalid hex data.")
    return ByteArray(length / 2) { ((this[it * 2].digitToInt(16) shl 4) or this[it * 2 + 1].digitToInt(16)).toByte() }
}
