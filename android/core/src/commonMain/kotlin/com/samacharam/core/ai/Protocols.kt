package com.samacharam.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class HttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
    /** The transport must enforce this at connect time: Cloud resolves only to public addresses, LocalNetwork only to private ones. */
    val location: Location,
    val timeoutMs: Int,
)

data class HttpResponse(val status: Int, val body: String)

/** Wire formats for each HTTP protocol. Pure functions: easy to test, no network here. */
object Protocols {
    /** Messages API version sent by the Anthropic adapter. This version rejects temperature/top_p/top_k. */
    const val ANTHROPIC_VERSION = "2026-10-21"

    /** Claude models that accept server-side refusal fallbacks with `fallbacks: "default"`. */
    private val ANTHROPIC_FALLBACK_MODELS = setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
    const val ANTHROPIC_FALLBACK_BETA = "server-side-fallback-2026-07-01"

    private fun base(p: ProviderConfig): String = (p.effectiveEndpoint() ?: throw AiException("${p.name} needs an endpoint URL.")).trimEnd('/')

    fun chatRequest(p: ProviderConfig, task: AiTask, input: TaskInput, key: String?): HttpRequest {
        val system = Prompts.system(task, input.labels)
        val user = Prompts.user(input)
        val location = p.location()
        return when (p.protocol) {
            Protocol.OpenAiCompatible -> HttpRequest(
                base(p) + "/chat/completions",
                jsonHeaders() + bearer(key),
                buildJsonObject {
                    put("model", p.model)
                    putJsonArray("messages") {
                        addJsonObject { put("role", "system"); put("content", system) }
                        addJsonObject { put("role", "user"); put("content", user) }
                    }
                    put("temperature", p.temperature)
                    put("max_tokens", p.maxOutputTokens)
                }.toString(),
                location, p.timeoutMs,
            )
            Protocol.Ollama -> HttpRequest(
                base(p) + "/api/chat",
                jsonHeaders() + bearer(key),
                buildJsonObject {
                    put("model", p.model)
                    putJsonArray("messages") {
                        addJsonObject { put("role", "system"); put("content", system) }
                        addJsonObject { put("role", "user"); put("content", user) }
                    }
                    put("stream", false)
                    put("format", "json")
                    putJsonObject("options") { put("temperature", p.temperature); put("num_predict", p.maxOutputTokens) }
                }.toString(),
                location, p.timeoutMs,
            )
            Protocol.Anthropic -> {
                val key = key?.takeIf { it.isNotBlank() } ?: throw AiException("${p.name} needs an API key.")
                val fallback = p.model in ANTHROPIC_FALLBACK_MODELS
                val headers = jsonHeaders() + mapOf("x-api-key" to key, "anthropic-version" to ANTHROPIC_VERSION) +
                    (if (fallback) mapOf("anthropic-beta" to ANTHROPIC_FALLBACK_BETA) else emptyMap())
                HttpRequest(
                    base(p) + "/v1/messages",
                    headers,
                    buildJsonObject {
                        put("model", p.model)
                        // Current models think adaptively and that output counts toward max_tokens; keep room for it.
                        put("max_tokens", maxOf(p.maxOutputTokens, 1_024))
                        put("system", system)
                        putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", user) } }
                        // Short structured tasks do not need deep reasoning.
                        putJsonObject("output_config") { put("effort", if (task == AiTask.Classification || task == AiTask.Reranking) "low" else "medium") }
                        if (fallback) put("fallbacks", "default")
                    }.toString(),
                    location, p.timeoutMs,
                )
            }
            Protocol.Gemini -> {
                val key = key?.takeIf { it.isNotBlank() } ?: throw AiException("${p.name} needs an API key.")
                HttpRequest(
                    base(p) + "/v1beta/models/${p.model}:generateContent",
                    jsonHeaders() + mapOf("x-goog-api-key" to key),
                    buildJsonObject {
                        putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
                        putJsonArray("contents") {
                            addJsonObject { put("role", "user"); putJsonArray("parts") { addJsonObject { put("text", user) } } }
                        }
                        putJsonObject("generationConfig") {
                            put("temperature", p.temperature)
                            put("maxOutputTokens", p.maxOutputTokens)
                            put("responseMimeType", "application/json")
                        }
                    }.toString(),
                    location, p.timeoutMs,
                )
            }
            Protocol.OnDeviceLiteRt, Protocol.OnDeviceOnnx -> throw AiException("On-device models do not use HTTP.")
        }
    }

    fun embeddingsRequest(p: ProviderConfig, text: String, key: String?): HttpRequest {
        val location = p.location()
        return when (p.protocol) {
            Protocol.OpenAiCompatible -> HttpRequest(
                base(p) + "/embeddings", jsonHeaders() + bearer(key),
                buildJsonObject { put("model", p.model); putJsonArray("input") { add(text) } }.toString(), location, p.timeoutMs,
            )
            Protocol.Ollama -> HttpRequest(
                base(p) + "/api/embed", jsonHeaders() + bearer(key),
                buildJsonObject { put("model", p.model); put("input", text) }.toString(), location, p.timeoutMs,
            )
            Protocol.Gemini -> {
                val key = key?.takeIf { it.isNotBlank() } ?: throw AiException("${p.name} needs an API key.")
                HttpRequest(
                    base(p) + "/v1beta/models/${p.model}:embedContent", jsonHeaders() + mapOf("x-goog-api-key" to key),
                    buildJsonObject { putJsonObject("content") { putJsonArray("parts") { addJsonObject { put("text", text) } } } }.toString(),
                    location, p.timeoutMs,
                )
            }
            else -> throw AiException("${p.protocol.name} does not provide embeddings.")
        }
    }

    /** Extracts the model's text reply. Throws AiException with a safe message for errors, refusals, and truncation. */
    fun chatText(p: ProviderConfig, response: HttpResponse): String {
        val root = parseObject(p, response)
        val text = when (p.protocol) {
            Protocol.OpenAiCompatible -> {
                val choice = root.arr("choices")?.firstOrNull() as? JsonObject
                if (choice?.str("finish_reason") == "length") throw AiException("${p.name} hit its output limit; raise the output budget.")
                (choice?.get("message") as? JsonObject)?.str("content")
            }
            Protocol.Ollama -> (root["message"] as? JsonObject)?.str("content")
            Protocol.Anthropic -> {
                when (root.str("stop_reason")) {
                    "refusal" -> throw AiException("${p.name} declined this request.")
                    "max_tokens" -> throw AiException("${p.name} hit its output limit; raise the output budget.")
                }
                // Responses can include thinking and fallback blocks; only text blocks carry the answer.
                root.arr("content")?.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }
                    ?.joinToString("")?.ifEmpty { null }
            }
            Protocol.Gemini -> {
                val candidate = root.arr("candidates")?.firstOrNull() as? JsonObject
                when (candidate?.str("finishReason")) {
                    "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" -> throw AiException("${p.name} declined this request.")
                    "MAX_TOKENS" -> throw AiException("${p.name} hit its output limit; raise the output budget.")
                }
                ((candidate?.get("content") as? JsonObject)?.arr("parts"))
                    ?.mapNotNull { (it as? JsonObject)?.str("text") }?.joinToString("")?.ifEmpty { null }
            }
            else -> null
        }
        return text ?: throw AiException("${p.name} returned no text.")
    }

    fun embeddings(p: ProviderConfig, response: HttpResponse): List<FloatArray> {
        val root = parseObject(p, response)
        val vectors: List<JsonElement> = when (p.protocol) {
            Protocol.OpenAiCompatible -> root.arr("data")?.mapNotNull { (it as? JsonObject)?.get("embedding") }.orEmpty()
            Protocol.Ollama -> root.arr("embeddings").orEmpty()
            Protocol.Gemini -> listOfNotNull((root["embedding"] as? JsonObject)?.get("values"))
            else -> emptyList()
        }
        return vectors.map { v ->
            val numbers = (v as? JsonArray) ?: throw AiException("${p.name} returned a malformed embedding.")
            FloatArray(numbers.size) { i -> (numbers[i] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: throw AiException("${p.name} returned a malformed embedding.") }
        }
    }

    private fun parseObject(p: ProviderConfig, response: HttpResponse): JsonObject {
        val parsed = runCatching { Json.parseToJsonElement(response.body) }.getOrNull() as? JsonObject
        if (response.status !in 200..299) {
            val detail = (parsed?.get("error") as? JsonObject)?.str("message") ?: (parsed?.get("error") as? JsonPrimitive)?.content
            throw AiException("${p.name} returned HTTP ${response.status}" + (detail?.let { ": ${it.take(200)}" } ?: "."))
        }
        return parsed ?: throw AiException("${p.name} returned invalid JSON.")
    }

    private fun jsonHeaders() = mapOf("content-type" to "application/json", "accept" to "application/json")
    private fun bearer(key: String?) = if (key.isNullOrBlank()) emptyMap() else mapOf("authorization" to "Bearer $key")
    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.arr(name: String): JsonArray? = this[name] as? JsonArray
}
