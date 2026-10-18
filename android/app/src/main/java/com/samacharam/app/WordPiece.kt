package com.samacharam.app

import java.text.Normalizer
import java.util.Locale

/** BERT uncased WordPiece only. Other tokenizer families must not use this adapter. */
class WordPiece(private val vocab: Map<String, Int>) {
    fun encode(text: String, limit: Int = 256): LongArray {
        val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val ids = mutableListOf(vocab["[CLS]"] ?: error("Missing [CLS]"))
        for (token in Regex("[\\p{L}\\p{N}]+|[^\\s\\p{L}\\p{N}]").findAll(normalized).map { it.value }) {
            if (token.length > 100) { ids.add(vocab["[UNK]"] ?: error("Missing [UNK]")); continue }
            val pieces = mutableListOf<Int>(); var start = 0; var failed = false
            while (start < token.length) {
                var end = token.length; var found: Int? = null
                while (end > start) {
                    found = vocab[(if (start > 0) "##" else "") + token.substring(start,end)]
                    if (found != null) break
                    end--
                }
                if (found == null) { failed = true; break }
                pieces.add(found); start = end
            }
            ids.addAll(if (failed) listOf(vocab["[UNK]"] ?: error("Missing [UNK]")) else pieces)
            if (ids.size >= limit-1) break
        }
        return (ids.take(limit-1) + (vocab["[SEP]"] ?: error("Missing [SEP]"))).map { it.toLong() }.toLongArray()
    }
}
