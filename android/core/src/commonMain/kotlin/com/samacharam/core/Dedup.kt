package com.samacharam.core

import kotlin.math.abs

private val STOPWORDS = setOf(
    "a", "an", "the", "and", "or", "of", "for", "to", "in", "on", "with", "from", "into", "by", "at", "as",
    "is", "are", "new", "its", "via", "how", "what", "why",
)

/** Lowercase word tokens of a title, without stopwords or single characters. */
fun titleTokens(title: String): Set<String> {
    val tokens = mutableSetOf<String>()
    val word = StringBuilder()
    fun flush() {
        // Numbers are kept even when short: "GPT 4" and "GPT 5" are different things.
        if (word.length >= 2 || (word.isNotEmpty() && word.all { it.isDigit() })) {
            val token = word.toString()
            if (token !in STOPWORDS) tokens += token
        }
        word.clear()
    }
    for (char in title.lowercase()) if (char.isLetterOrDigit()) word.append(char) else flush()
    flush()
    return tokens
}

/** 64-bit SimHash of a token set: similar titles produce hashes with few differing bits. */
fun simHash(tokens: Set<String>): Long {
    if (tokens.isEmpty()) return 0L
    val votes = IntArray(64)
    for (token in tokens) {
        val hash = fnv64(token)
        for (bit in 0 until 64) votes[bit] += if ((hash ushr bit) and 1L == 1L) 1 else -1
    }
    var result = 0L
    for (bit in 0 until 64) if (votes[bit] > 0) result = result or (1L shl bit)
    return result
}

fun hamming(a: Long, b: Long): Int = (a xor b).countOneBits()

fun jaccard(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() && b.isEmpty()) return 1.0
    return (a intersect b).size.toDouble() / (a union b).size
}

private const val DAY = 86_400_000L

/**
 * Conservative near-duplicate test for syndicated copies of one announcement.
 * Requires strong title overlap and close publication dates; a SimHash pre-filter keeps it cheap.
 * Topic similarity alone never qualifies.
 */
fun isNearDuplicate(
    titleA: String, publishedA: Long?,
    titleB: String, publishedB: Long?,
    maxDays: Int = 3,
): Boolean {
    val a = titleTokens(titleA)
    val b = titleTokens(titleB)
    if (a.size < 4 || b.size < 4) return a == b && a.isNotEmpty() && withinDays(publishedA, publishedB, maxDays)
    if (hamming(simHash(a), simHash(b)) > 12) return false
    return jaccard(a, b) >= 0.8 && withinDays(publishedA, publishedB, maxDays)
}

private fun withinDays(a: Long?, b: Long?, days: Int): Boolean =
    a != null && b != null && abs(a - b) <= days * DAY
