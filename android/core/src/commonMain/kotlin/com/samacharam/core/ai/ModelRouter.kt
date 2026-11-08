package com.samacharam.core.ai

import com.samacharam.core.formatDate

/** Sends HTTP. Implementations must check resolved addresses against [HttpRequest.location] and must not follow redirects. */
fun interface Transport {
    fun send(request: HttpRequest): HttpResponse
}

/** Device-local secret storage (Android Keystore). Keys are never synced or exported. */
interface SecretStore {
    fun get(providerId: String): String?
    fun put(providerId: String, secret: String)
    fun remove(providerId: String)
}

/** On-device inference. Implemented on Android by the ONNX Runtime and LiteRT-LM adapters. */
interface OnDeviceRuntime {
    /** Text generation for LiteRT-LM bundles; returns the raw reply, validated like any other model. */
    fun generate(bundle: String, system: String, user: String, maxOutputTokens: Int): String
    fun embed(bundle: String, text: String): FloatArray
    /** Raw classifier label scores; mapped onto the owner's topics by exact label name. */
    fun classify(bundle: String, text: String): Map<String, Float>
}

/** Persists the per-day cloud request count. */
interface UsageCounter {
    /** Atomically increments today's count unless it already reached [limit]; returns false when the budget is spent. */
    fun tryConsume(day: String, limit: Int): Boolean
}

/**
 * Runs one task on its route: primary, then the optional fallback.
 * Privacy rules are checked before any key is read or any request is built.
 */
class ModelRouter(
    private val settings: () -> AiSettings,
    private val secrets: SecretStore,
    private val transport: Transport,
    private val usage: UsageCounter,
    private val onDevice: OnDeviceRuntime? = null,
    private val clock: () -> Long,
) {
    fun run(task: AiTask, input: TaskInput, providerId: String? = null): ModelResult {
        if (input.text.isBlank()) throw AiException("There is no source text to process.")
        if (input.question.length > 2_000) throw AiException("The question is too long.")
        val current = settings()
        val route = current.route(task)
        val ids = if (providerId != null) listOf(providerId) else listOfNotNull(route.primary, route.fallback).distinct()
        val failures = ArrayList<String>()
        val started = clock()
        ids.forEachIndexed { index, id ->
            try {
                val (provider, output) = if (id == BUILTIN) {
                    null to Builtin.run(task, input)
                } else {
                    val p = current.provider(id) ?: throw AiException("The model selected for ${task.wire} was removed.")
                    p to execute(p, task, input, current.policy, isFallback = index > 0)
                }
                return ModelResult(
                    task = task,
                    providerId = provider?.id ?: BUILTIN,
                    providerName = provider?.name ?: "Built-in rules",
                    model = provider?.model ?: "rules-v1",
                    location = provider?.location() ?: Location.Device,
                    fallbackUsed = index > 0,
                    priorFailures = failures.toList(),
                    milliseconds = clock() - started,
                    output = output,
                )
            } catch (e: AiException) {
                failures += e.message ?: "Model failed."
            }
        }
        throw AiException(if (failures.isEmpty()) "No model is configured for ${task.wire}." else failures.joinToString(" Then: "))
    }

    private fun execute(p: ProviderConfig, task: AiTask, input: TaskInput, policy: AiPolicy, isFallback: Boolean): TaskOutput {
        if (task !in p.capabilities) throw AiException("${p.name} is not set up for ${task.wire}.")
        if (input.text.length > p.maxInputChars) throw AiException("The source is longer than ${p.name}'s input budget.")
        val location = p.location()
        if (location == Location.Cloud) {
            if (policy.localOnly) throw AiException("Local-only mode blocked ${p.name}; no text was sent.")
            if (!p.allowCloud) throw AiException("${p.name} has no permission to receive source text; no text was sent.")
            if (isFallback && !policy.allowCloudFallback) throw AiException("Cloud fallback is off; no text was sent to ${p.name}.")
            if (!usage.tryConsume(formatDate(clock()), policy.dailyCloudRequests)) {
                throw AiException("Today's cloud request limit (${policy.dailyCloudRequests}) is reached.")
            }
        }
        if (location == Location.Device) return onDeviceRun(p, task, input)

        val key = secrets.get(p.id)
        val response = try {
            transport.send(if (task == AiTask.Embeddings) Protocols.embeddingsRequest(p, input.text, key) else Protocols.chatRequest(p, task, input, key))
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException("Could not reach ${p.name}. Check the connection and endpoint.")
        }
        return try {
            if (task == AiTask.Embeddings) OutputValidation.vectors(Protocols.embeddings(p, response))
            else OutputValidation.validate(task, OutputValidation.parseJson(Protocols.chatText(p, response)), input)
        } catch (e: AiException) {
            throw AiException(redact(e.message ?: "Model failed.", key))
        }
    }

    private fun onDeviceRun(p: ProviderConfig, task: AiTask, input: TaskInput): TaskOutput {
        val runtime = onDevice ?: throw AiException("On-device models are not available on this platform.")
        try {
            return when (p.protocol) {
                Protocol.OnDeviceOnnx -> when (task) {
                    AiTask.Embeddings -> OutputValidation.vectors(listOf(runtime.embed(p.model, input.text)))
                    AiTask.Classification -> {
                        val allowed = input.labels.associateBy { it.lowercase() }
                        val labels = runtime.classify(p.model, input.text).filterValues { it >= 0.5f }.keys
                            .mapNotNull { allowed[it.lowercase()] }.distinct()
                        TaskOutput.Labels(labels, "On-device classifier. Only labels that match your topic names are used.")
                    }
                    else -> throw AiException("ONNX bundles support classification and embeddings only.")
                }
                Protocol.OnDeviceLiteRt -> {
                    val reply = runtime.generate(p.model, Prompts.system(task, input.labels), Prompts.user(input), p.maxOutputTokens)
                    OutputValidation.validate(task, OutputValidation.parseJson(reply), input)
                }
                else -> throw AiException("${p.protocol.name} is not an on-device protocol.")
            }
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException("${p.name} failed on this device. Check the bundle format and available memory.")
        }
    }

    /** Provider error text can echo request headers; never let a key reach the UI or logs. */
    private fun redact(message: String, key: String?): String =
        if (key.isNullOrBlank() || key.length < 4) message else message.replace(key, "••••")
}
