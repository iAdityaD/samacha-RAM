package com.samacharam.core.ai

/** Tasks that can be routed to a model. Recommendations themselves stay rule-based unless the owner opts in. */
enum class AiTask(val wire: String) {
    Classification("classification"),
    Summarization("summarization"),
    Embeddings("embeddings"),
    Reranking("reranking"),
    Evidence("evidence"),
    QA("qa");

    companion object {
        fun fromWire(value: String): AiTask? = entries.firstOrNull { it.wire == value }
    }
}

/** How the app talks to a model. */
enum class Protocol(val wire: String, val supports: Set<AiTask>, val onDevice: Boolean = false) {
    OpenAiCompatible("openai", AiTask.entries.toSet()),
    Ollama("ollama", AiTask.entries.toSet()),
    Anthropic("anthropic", AiTask.entries.toSet() - AiTask.Embeddings), // no embeddings endpoint
    Gemini("gemini", AiTask.entries.toSet()),
    OnDeviceLiteRt("litert", setOf(AiTask.Classification, AiTask.Summarization, AiTask.QA, AiTask.Evidence, AiTask.Reranking), onDevice = true),
    OnDeviceOnnx("onnx", setOf(AiTask.Classification, AiTask.Embeddings), onDevice = true);

    companion object {
        fun fromWire(value: String): Protocol? = entries.firstOrNull { it.wire == value }
    }
}

/** Where source text goes. Derived from the protocol and endpoint, never asserted by the user. */
enum class Location { Device, LocalNetwork, Cloud }

data class ProviderConfig(
    val id: String,
    val name: String,
    val protocol: Protocol,
    /** Provider model ID, Ollama model name, or on-device bundle name. */
    val model: String,
    /** Base URL; null for on-device models and for Anthropic/Gemini defaults. */
    val endpoint: String? = null,
    val capabilities: Set<AiTask>,
    /** Explicit consent to send source text to a cloud endpoint. */
    val allowCloud: Boolean = false,
    val maxInputChars: Int = 24_000,
    val maxOutputTokens: Int = 2_048,
    /** Ignored by protocols that reject sampling parameters (Anthropic API version 2026-10-21). */
    val temperature: Double = 0.0,
    val timeoutMs: Int = 90_000,
)

const val BUILTIN = "builtin"

/** Per-task route: [primary] is a provider ID or [BUILTIN]; [fallback] is optional. */
data class Route(val primary: String = BUILTIN, val fallback: String? = null)

data class AiPolicy(
    /** Blocks every cloud provider before any network access. */
    val localOnly: Boolean = false,
    /** A second, separate opt-in: a failed primary may fall back to a cloud provider. */
    val allowCloudFallback: Boolean = false,
    /** Cloud attempts per UTC day, counted before sending because failed requests may still be billed. */
    val dailyCloudRequests: Int = 100,
    /** Opt-in: a reranking model may reorder briefing items within their layers. */
    val assistedRanking: Boolean = false,
)

data class AiSettings(
    val providers: List<ProviderConfig> = emptyList(),
    val routes: Map<AiTask, Route> = emptyMap(),
    val policy: AiPolicy = AiPolicy(),
) {
    fun route(task: AiTask): Route = routes[task] ?: Route()
    fun provider(id: String): ProviderConfig? = providers.firstOrNull { it.id == id }
}

data class TaskInput(
    val text: String,
    val question: String = "",
    /** Allowed classification labels (normally the owner's topics). */
    val labels: List<String> = emptyList(),
)

data class CitedSentence(val text: String, val quote: String)

sealed class TaskOutput {
    data class Labels(val labels: List<String>, val notice: String) : TaskOutput()
    data class Cited(val sentences: List<CitedSentence>, val notice: String) : TaskOutput()
    data class Vectors(val vectors: List<FloatArray>) : TaskOutput() {
        val dimensions: Int get() = vectors.first().size
    }
    data class Score(val score: Double, val reason: String) : TaskOutput()
}

data class ModelResult(
    val task: AiTask,
    val providerId: String,
    val providerName: String,
    val model: String,
    val location: Location,
    val fallbackUsed: Boolean,
    val priorFailures: List<String>,
    val milliseconds: Long,
    val output: TaskOutput,
)

/** Every failure the router reports. Messages are safe to show: they never contain keys or source text. */
class AiException(message: String) : Exception(message)
