package com.samacharam.core

/** Raw DEFLATE compression, provided per platform. */
expect fun deflateRaw(data: ByteArray): ByteArray

/** Raw DEFLATE decompression. Throws IllegalArgumentException when output would exceed [maxBytes] or data is corrupt. */
expect fun inflateRaw(data: ByteArray, maxBytes: Int): ByteArray

/**
 * Stored text format: one tag byte, then the payload.
 *   0 = UTF-8 as is, 1 = raw DEFLATE of UTF-8.
 * Short text is stored plain because compression headers would outweigh any saving.
 */
object TextCodec {
    const val COMPRESS_FROM_BYTES = 512
    const val MAX_TEXT_BYTES = 32 * 1024 * 1024
    private const val PLAIN: Byte = 0
    private const val DEFLATED: Byte = 1

    fun encode(text: String): ByteArray {
        val raw = text.encodeToByteArray()
        if (raw.size >= COMPRESS_FROM_BYTES) {
            val packed = deflateRaw(raw)
            if (packed.size + 1 < raw.size) return byteArrayOf(DEFLATED) + packed
        }
        return byteArrayOf(PLAIN) + raw
    }

    fun decode(stored: ByteArray): String {
        require(stored.isNotEmpty()) { "Empty stored text." }
        val payload = stored.copyOfRange(1, stored.size)
        return when (stored[0]) {
            PLAIN -> payload.decodeToString()
            DEFLATED -> inflateRaw(payload, MAX_TEXT_BYTES).decodeToString()
            else -> throw IllegalArgumentException("Unknown text encoding ${stored[0]}.")
        }
    }
}
