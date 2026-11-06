package com.samacharam.core

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** int8 symmetric quantization: one float scale plus one byte per dimension (≈4× smaller than float32). */
class QuantizedVector(val scale: Float, val values: ByteArray) {
    val dimensions: Int get() = values.size

    fun toBytes(): ByteArray {
        val bits = scale.toRawBits()
        return byteArrayOf((bits ushr 24).toByte(), (bits ushr 16).toByte(), (bits ushr 8).toByte(), bits.toByte()) + values
    }

    companion object {
        fun fromBytes(bytes: ByteArray): QuantizedVector {
            require(bytes.size >= 5) { "Vector blob is too short." }
            val bits = ((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or
                ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
            return QuantizedVector(Float.fromBits(bits), bytes.copyOfRange(4, bytes.size))
        }
    }
}

fun quantize(vector: FloatArray): QuantizedVector {
    require(vector.isNotEmpty() && vector.all { it.isFinite() }) { "Vector must be non-empty and finite." }
    val maxAbs = vector.maxOf { abs(it) }
    val scale = if (maxAbs == 0f) 1f else maxAbs / 127f
    return QuantizedVector(scale, ByteArray(vector.size) { (vector[it] / scale).roundToInt().coerceIn(-127, 127).toByte() })
}

/** Cosine similarity on quantized values. The per-vector scale cancels out. */
fun cosine(a: QuantizedVector, b: QuantizedVector): Double {
    require(a.dimensions == b.dimensions) { "Vectors from different embedding spaces cannot be compared." }
    var dot = 0L; var na = 0L; var nb = 0L
    for (i in 0 until a.dimensions) {
        val x = a.values[i].toLong(); val y = b.values[i].toLong()
        dot += x * y; na += x * x; nb += y * y
    }
    return if (na == 0L || nb == 0L) 0.0 else dot / (sqrt(na.toDouble()) * sqrt(nb.toDouble()))
}
