package com.samacharam.core

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

actual fun deflateRaw(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
    try {
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 16)
        val buffer = ByteArray(8192)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}

actual fun inflateRaw(data: ByteArray, maxBytes: Int): ByteArray {
    val inflater = Inflater(true)
    try {
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 3)
        val buffer = ByteArray(8192)
        while (!inflater.finished()) {
            val n = try { inflater.inflate(buffer) } catch (e: DataFormatException) {
                throw IllegalArgumentException("Corrupt compressed text.", e)
            }
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                throw IllegalArgumentException("Truncated compressed text.")
            }
            if (out.size() + n > maxBytes) throw IllegalArgumentException("Compressed text exceeds the size limit.")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}
