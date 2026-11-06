package com.samacharam.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Validates model replies. Any shape problem becomes an AiException so the router can try the fallback;
 * nothing unvalidated reaches the UI. Quote checks prove a quote exists in the source, not that it supports the text.
 */
object OutputValidation {
    const val CITED_NOTICE = "Quotes were found in the source. Whether they support each statement has not been independently reviewed."

    fun parseJson(raw: String): JsonObject {
        var text = raw.trim()
        if (text.startsWith("```")) text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiException("Model reply was not JSON.")
        return runCatching { Json.parseToJsonElement(text.substring(start, end + 1)) }.getOrNull() as? JsonObject
            ?: throw AiException("Model reply was not a JSON object.")
    }

    fun validate(task: AiTask, reply: JsonObject, input: TaskInput): TaskOutput = when (task) {
        AiTask.Classification -> labels(reply["labels"], input.labels)
        AiTask.Reranking -> {
            val score = (reply["score"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            if (score == null || score.isNaN() || score !in 0.0..1.0) throw AiException("Reranker must return a score between 0 and 1.")
            TaskOutput.Score(score, (reply["reason"] as? JsonPrimitive)?.content?.take(500) ?: "")
        }
        AiTask.Summarization, AiTask.QA, AiTask.Evidence -> cited(reply["sentences"], input.text)
        AiTask.Embeddings -> throw AiException("Embeddings are not returned as JSON text.")
    }

    fun labels(value: JsonElement?, allowed: List<String>): TaskOutput.Labels {
        val list = value as? JsonArray ?: throw AiException("Classifier must return a labels list.")
        val canonical = allowed.associateBy { it.lowercase() }
        val result = list.map { element ->
            val label = (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()
            canonical[label] ?: throw AiException("Classifier returned a topic that is not one of yours.")
        }.distinct()
        return TaskOutput.Labels(result, "Model-suggested topics. Confirm before relying on them.")
    }

    fun cited(value: JsonElement?, source: String): TaskOutput.Cited {
        val list = value as? JsonArray ?: throw AiException("Reply must contain a sentences list.")
        val normalizedSource = normalize(source)
        val sentences = list.take(8).map { element ->
            val o = element as? JsonObject ?: throw AiException("Each sentence must be an object with text and quote.")
            val text = (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            val quote = (o["quote"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            if (text.isEmpty() || quote.length < 8 || normalize(quote) !in normalizedSource) {
                throw AiException("Citation check failed: a quoted passage does not appear in the source.")
            }
            CitedSentence(text.take(2_000), quote.take(3_000))
        }
        return TaskOutput.Cited(sentences, CITED_NOTICE)
    }

    fun vectors(vectors: List<FloatArray>): TaskOutput.Vectors {
        if (vectors.isEmpty() || vectors.any { it.isEmpty() || !it.all(Float::isFinite) }) throw AiException("Embedding must be a non-empty finite vector.")
        if (vectors.map { it.size }.toSet().size != 1) throw AiException("Embeddings have inconsistent dimensions.")
        return TaskOutput.Vectors(vectors)
    }

    /** Whitespace differences (line breaks, double spaces) are not treated as different quotes; words must match exactly. */
    private fun normalize(value: String) = value.replace(Regex("\\s+"), " ").trim()
}
