package com.samacharam.core.ai

import com.samacharam.core.Article
import com.samacharam.core.ContentType
import com.samacharam.core.Layer
import com.samacharam.core.Profile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val SOURCE = "The team reports a 12% latency reduction on a single GPU. Results were measured on one benchmark only."

private fun cloud(id: String = "c", protocol: Protocol = Protocol.OpenAiCompatible, model: String = "m", caps: Set<AiTask> = setOf(AiTask.Summarization), allow: Boolean = true) =
    ProviderConfig(id, "Cloud $id", protocol, model, if (protocol == Protocol.OpenAiCompatible) "https://api.example.com/v1" else null, caps, allowCloud = allow)

private fun lan(id: String = "l", caps: Set<AiTask> = setOf(AiTask.Summarization)) =
    ProviderConfig(id, "Desktop Ollama", Protocol.Ollama, "llama3", "http://192.168.1.20:11434", caps)

private fun body(request: HttpRequest): JsonObject = Json.parseToJsonElement(request.body).jsonObject

class ProviderValidationTest {
    @Test fun cloudNeedsHttpsAndConsent() {
        assertFailsWith<AiException> { validateProvider(cloud().copy(endpoint = "http://api.example.com/v1")) }
        assertFailsWith<AiException> { validateProvider(cloud(allow = false)) }
        validateProvider(cloud())
    }

    @Test fun localNetworkAllowsPlainHttpAndIsNotCloud() {
        validateProvider(lan())
        assertEquals(Location.LocalNetwork, lan().location())
        assertEquals(Location.LocalNetwork, lan().copy(endpoint = "http://my-desktop:8080/v1").location())
        assertEquals(Location.LocalNetwork, lan().copy(endpoint = "http://100.101.5.9:11434").location()) // Tailscale address range
        assertEquals(Location.Cloud, cloud().location())
    }

    @Test fun capabilitiesMustMatchProtocol() {
        assertFailsWith<AiException> { validateProvider(cloud(protocol = Protocol.Anthropic, caps = setOf(AiTask.Embeddings))) }
        assertFailsWith<AiException> {
            validateProvider(ProviderConfig("d", "Phone", Protocol.OnDeviceOnnx, "bert", null, setOf(AiTask.Summarization)))
        }
        validateProvider(ProviderConfig("d", "Phone", Protocol.OnDeviceOnnx, "bert-topics", null, setOf(AiTask.Classification)))
    }

    @Test fun rejectsCredentialsInUrlsAndBadBundles() {
        assertFailsWith<AiException> { validateProvider(cloud().copy(endpoint = "https://user:pw@api.example.com/v1")) }
        assertFailsWith<AiException> { validateProvider(ProviderConfig("d", "Phone", Protocol.OnDeviceLiteRt, "../x", null, setOf(AiTask.QA))) }
    }
}

class ProtocolsTest {
    private val input = TaskInput(SOURCE, "What changed?", listOf("LLMs", "Hardware"))

    @Test fun openAiCompatibleRequest() {
        val r = Protocols.chatRequest(cloud(), AiTask.Summarization, input, "sk-test")
        assertEquals("https://api.example.com/v1/chat/completions", r.url)
        assertEquals("Bearer sk-test", r.headers["authorization"])
        val b = body(r)
        assertEquals("m", b["model"]!!.jsonPrimitive.content)
        assertEquals(2, b["messages"]!!.jsonArray.size)
        assertEquals(Location.Cloud, r.location)
    }

    @Test fun anthropicRequestUsesCurrentApiVersionWithoutSampling() {
        val r = Protocols.chatRequest(cloud(protocol = Protocol.Anthropic, model = "claude-opus-5-5", caps = setOf(AiTask.Classification)), AiTask.Classification, input, "key-1")
        assertEquals("https://api.anthropic.com/v1/messages", r.url)
        assertEquals("key-1", r.headers["x-api-key"])
        assertEquals("2026-10-21", r.headers["anthropic-version"])
        assertEquals(Protocols.ANTHROPIC_FALLBACK_BETA, r.headers["anthropic-beta"])
        val b = body(r)
        assertFalse("temperature" in b, "API version 2026-10-21 rejects temperature")
        assertEquals("low", b["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("default", b["fallbacks"]!!.jsonPrimitive.content)
        assertTrue(b["system"]!!.jsonPrimitive.content.contains("LLMs, Hardware"))
    }

    @Test fun anthropicFallbackOnlyForSupportedModels() {
        val r = Protocols.chatRequest(cloud(protocol = Protocol.Anthropic, model = "claude-haiku-5-5"), AiTask.Summarization, input, "k")
        assertEquals(null, r.headers["anthropic-beta"])
        assertFalse("fallbacks" in body(r))
    }

    @Test fun keyedCloudProtocolsRequireAKey() {
        assertFailsWith<AiException> { Protocols.chatRequest(cloud(protocol = Protocol.Anthropic), AiTask.Summarization, input, null) }
        assertFailsWith<AiException> { Protocols.chatRequest(cloud(protocol = Protocol.Gemini), AiTask.Summarization, input, "") }
    }

    @Test fun geminiAndOllamaRequests() {
        val g = Protocols.chatRequest(cloud(protocol = Protocol.Gemini, model = "gemini-pro"), AiTask.Summarization, input, "g-key")
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent", g.url)
        assertEquals("g-key", g.headers["x-goog-api-key"])
        assertEquals("application/json", body(g)["generationConfig"]!!.jsonObject["responseMimeType"]!!.jsonPrimitive.content)
        val o = Protocols.chatRequest(lan(), AiTask.Summarization, input, null)
        assertEquals("http://192.168.1.20:11434/api/chat", o.url)
        assertEquals("json", body(o)["format"]!!.jsonPrimitive.content)
        assertEquals(null, o.headers["authorization"])
    }

    @Test fun anthropicResponseReadsOnlyTextBlocks() {
        val p = cloud(protocol = Protocol.Anthropic)
        val reply = """{"content":[{"type":"thinking","thinking":""},{"type":"text","text":"{\"sentences\":[]}"}],"stop_reason":"end_turn"}"""
        assertEquals("{\"sentences\":[]}", Protocols.chatText(p, HttpResponse(200, reply)))
        assertFailsWith<AiException> { Protocols.chatText(p, HttpResponse(200, """{"content":[],"stop_reason":"refusal"}""")) }
        assertFailsWith<AiException> { Protocols.chatText(p, HttpResponse(200, """{"content":[{"type":"text","text":"{"}],"stop_reason":"max_tokens"}""")) }
    }

    @Test fun httpErrorsAreSummarized() {
        val e = assertFailsWith<AiException> { Protocols.chatText(cloud(), HttpResponse(401, """{"error":{"message":"Invalid API key"}}""")) }
        assertEquals("Cloud c returned HTTP 401: Invalid API key", e.message)
    }

    @Test fun embeddingsParsePerProtocol() {
        val p = cloud(caps = setOf(AiTask.Embeddings))
        assertEquals(3, Protocols.embeddings(p, HttpResponse(200, """{"data":[{"embedding":[0.1,0.2,0.3]}]}""")).single().size)
        assertEquals(2, Protocols.embeddings(lan(), HttpResponse(200, """{"embeddings":[[1,2]]}""")).single().size)
        assertFailsWith<AiException> { Protocols.embeddingsRequest(cloud(protocol = Protocol.Anthropic), "x", "k") }
    }
}

class OutputValidationTest {
    private val input = TaskInput(SOURCE, labels = listOf("LLMs", "Hardware"))

    @Test fun acceptsFencedJsonAndWhitespaceDifferences() {
        val reply = OutputValidation.parseJson("```json\n{\"sentences\":[{\"text\":\"Latency fell 12%.\",\"quote\":\"a 12% latency\\n reduction on a single GPU\"}]}\n```")
        val out = OutputValidation.validate(AiTask.Summarization, reply, input) as TaskOutput.Cited
        assertEquals("Latency fell 12%.", out.sentences.single().text)
    }

    @Test fun rejectsInventedQuotesAndMalformedShapes() {
        assertFailsWith<AiException> {
            OutputValidation.validate(AiTask.Summarization, OutputValidation.parseJson("""{"sentences":[{"text":"x","quote":"a 15% latency reduction"}]}"""), input)
        }
        assertFailsWith<AiException> { OutputValidation.validate(AiTask.QA, OutputValidation.parseJson("""{"sentences":["just a string"]}"""), input) }
        assertFailsWith<AiException> { OutputValidation.validate(AiTask.QA, OutputValidation.parseJson("""{"answer":"no list"}"""), input) }
        assertFailsWith<AiException> { OutputValidation.parseJson("""["not","an","object"]""") }
        assertFailsWith<AiException> { OutputValidation.parseJson("I cannot help") }
    }

    @Test fun labelsMustBeTheOwnersTopics() {
        val ok = OutputValidation.validate(AiTask.Classification, OutputValidation.parseJson("""{"labels":["hardware","LLMs","LLMs"]}"""), input) as TaskOutput.Labels
        assertEquals(listOf("Hardware", "LLMs"), ok.labels)
        assertFailsWith<AiException> { OutputValidation.validate(AiTask.Classification, OutputValidation.parseJson("""{"labels":["Crypto"]}"""), input) }
    }

    @Test fun rerankScoreMustBeInRange() {
        assertFailsWith<AiException> { OutputValidation.validate(AiTask.Reranking, OutputValidation.parseJson("""{"score":1.4}"""), input) }
        assertFailsWith<AiException> { OutputValidation.validate(AiTask.Reranking, OutputValidation.parseJson("""{"score":"0.5"}"""), input) }
        assertEquals(0.5, (OutputValidation.validate(AiTask.Reranking, OutputValidation.parseJson("""{"score":0.5}"""), input) as TaskOutput.Score).score)
    }
}

private class FakeSecrets(val values: MutableMap<String, String> = mutableMapOf()) : SecretStore {
    override fun get(providerId: String) = values[providerId]
    override fun put(providerId: String, secret: String) { values[providerId] = secret }
    override fun remove(providerId: String) { values.remove(providerId) }
}

private class FakeUsage(var used: Int = 0) : UsageCounter {
    override fun tryConsume(day: String, limit: Int): Boolean = if (used >= limit) false else { used++; true }
}

private class FakeTransport(val reply: (HttpRequest) -> HttpResponse) : Transport {
    val sent = mutableListOf<HttpRequest>()
    override fun send(request: HttpRequest): HttpResponse { sent += request; return reply(request) }
}

private fun quoted(value: String) = kotlinx.serialization.json.JsonPrimitive(value).toString()

private fun openAiReply(content: String) = HttpResponse(200, """{"choices":[{"message":{"content":${quoted(content)}},"finish_reason":"stop"}]}""")

class ModelRouterTest {
    private val good = """{"sentences":[{"text":"Latency fell.","quote":"a 12% latency reduction"}]}"""

    private fun router(settings: AiSettings, transport: Transport, usage: UsageCounter = FakeUsage(), secrets: SecretStore = FakeSecrets(mutableMapOf("c" to "sk-secret-123")), onDevice: OnDeviceRuntime? = null) =
        ModelRouter({ settings }, secrets, transport, usage, onDevice, clock = { 1_800_000_000_000L })

    @Test fun builtInRulesAreTheDefault() {
        val transport = FakeTransport { error("no network expected") }
        val result = router(AiSettings(), transport).run(AiTask.Summarization, TaskInput(SOURCE))
        assertEquals(BUILTIN, result.providerId)
        assertTrue(transport.sent.isEmpty())
        val labels = router(AiSettings(), transport).run(AiTask.Classification, TaskInput("New GPU accelerator for inference", labels = listOf("Hardware", "AI audio", "Robotics")))
        assertEquals(listOf("Hardware"), (labels.output as TaskOutput.Labels).labels)
    }

    @Test fun builtInNeverFakesModelOnlyTasks() {
        val e = assertFailsWith<AiException> { router(AiSettings(), FakeTransport { error("") }).run(AiTask.Embeddings, TaskInput("text")) }
        assertTrue(e.message!!.contains("needs a connected model"))
    }

    @Test fun cloudPrimaryWorks() {
        val settings = AiSettings(listOf(cloud()), mapOf(AiTask.Summarization to Route("c")))
        val transport = FakeTransport { openAiReply(good) }
        val result = router(settings, transport).run(AiTask.Summarization, TaskInput(SOURCE))
        assertEquals("c", result.providerId)
        assertEquals(Location.Cloud, result.location)
        assertEquals(1, transport.sent.size)
    }

    @Test fun failedPrimaryFallsBackAndReportsWhy() {
        val settings = AiSettings(listOf(cloud(), lan()), mapOf(AiTask.Summarization to Route("c", "l")))
        val transport = FakeTransport { r ->
            if (r.location == Location.Cloud) openAiReply("""{"sentences":[{"text":"x","quote":"invented passage here"}]}""")
            else HttpResponse(200, """{"message":{"content":${quoted(good)}}}""")
        }
        val result = router(settings, transport).run(AiTask.Summarization, TaskInput(SOURCE))
        assertEquals("l", result.providerId)
        assertTrue(result.fallbackUsed)
        assertTrue(result.priorFailures.single().startsWith("Citation check failed"))
    }

    @Test fun localOnlyBlocksCloudBeforeAnyRequest() {
        val settings = AiSettings(listOf(cloud()), mapOf(AiTask.Summarization to Route("c", BUILTIN)), AiPolicy(localOnly = true))
        val transport = FakeTransport { error("must not send") }
        val result = router(settings, transport).run(AiTask.Summarization, TaskInput(SOURCE))
        assertEquals(BUILTIN, result.providerId)
        assertTrue(transport.sent.isEmpty())
        assertTrue(result.priorFailures.single().contains("Local-only"))
    }

    @Test fun cloudFallbackNeedsItsOwnOptIn() {
        val settings = AiSettings(listOf(lan(), cloud()), mapOf(AiTask.Summarization to Route("l", "c")))
        val transport = FakeTransport { r -> if (r.location == Location.LocalNetwork) HttpResponse(500, "{}") else error("must not send") }
        val e = assertFailsWith<AiException> { router(settings, transport).run(AiTask.Summarization, TaskInput(SOURCE)) }
        assertTrue(e.message!!.contains("Cloud fallback is off"))
        assertEquals(1, transport.sent.size)
    }

    @Test fun dailyCloudLimitIsEnforced() {
        val settings = AiSettings(listOf(cloud()), mapOf(AiTask.Summarization to Route("c")), AiPolicy(dailyCloudRequests = 1))
        val usage = FakeUsage()
        val transport = FakeTransport { openAiReply(good) }
        router(settings, transport, usage).run(AiTask.Summarization, TaskInput(SOURCE))
        val e = assertFailsWith<AiException> { router(settings, transport, usage).run(AiTask.Summarization, TaskInput(SOURCE)) }
        assertTrue(e.message!!.contains("limit"))
        assertEquals(1, transport.sent.size)
    }

    @Test fun keysNeverAppearInErrors() {
        val settings = AiSettings(listOf(cloud()), mapOf(AiTask.Summarization to Route("c")))
        val transport = FakeTransport { HttpResponse(401, """{"error":{"message":"Key sk-secret-123 is invalid"}}""") }
        val e = assertFailsWith<AiException> { router(settings, transport).run(AiTask.Summarization, TaskInput(SOURCE)) }
        assertFalse(e.message!!.contains("sk-secret-123"), e.message)
    }

    @Test fun networkExceptionsBecomeSafeMessages() {
        val settings = AiSettings(listOf(cloud()), mapOf(AiTask.Summarization to Route("c")))
        val e = assertFailsWith<AiException> { router(settings, FakeTransport { throw IllegalStateException("socket at 10.0.0.1") }).run(AiTask.Summarization, TaskInput(SOURCE)) }
        assertEquals("Could not reach Cloud c. Check the connection and endpoint.", e.message)
    }

    @Test fun onDeviceModelsRunWithoutNetwork() {
        val device = ProviderConfig("d", "Phone model", Protocol.OnDeviceLiteRt, "gemma", null, setOf(AiTask.Summarization))
        val runtime = object : OnDeviceRuntime {
            override fun generate(bundle: String, system: String, user: String, maxOutputTokens: Int) = good
            override fun embed(bundle: String, text: String) = FloatArray(0)
            override fun classify(bundle: String, text: String) = emptyMap<String, Float>()
        }
        val transport = FakeTransport { error("no network") }
        val result = router(AiSettings(listOf(device), mapOf(AiTask.Summarization to Route("d"))), transport, onDevice = runtime).run(AiTask.Summarization, TaskInput(SOURCE))
        assertEquals(Location.Device, result.location)
        assertTrue(transport.sent.isEmpty())
    }
}

class AssistedRankingTest {
    private val profile = Profile(mapOf("LLMs" to Layer.Core, "NLP" to Layer.Near, "Crypto" to Layer.Muted))
    private fun a(id: Long, topic: String) = Article(id, "https://x/$id", "s", "T$id", "", id, 0, ContentType.Blog, listOf(topic), "", 1)

    @Test fun reordersOnlyWithinLayersAndNeverShowsMuted() {
        val ranked = listOf(a(1, "LLMs"), a(2, "LLMs"), a(3, "NLP"), a(4, "NLP"), a(5, "Crypto"))
        val scores = mapOf(1L to 0.1, 2L to 0.9, 3L to 0.2, 4L to 0.8, 5L to 1.0)
        assertEquals(listOf(2L, 1L, 4L, 3L), AssistedRanking.apply(ranked, profile, scores).map { it.id })
    }

    @Test fun nearItemCannotJumpAboveCore() {
        val ranked = listOf(a(1, "LLMs"), a(3, "NLP"))
        assertEquals(listOf(1L, 3L), AssistedRanking.apply(ranked, profile, mapOf(1L to 0.0, 3L to 1.0)).map { it.id })
    }

    @Test fun questionDescribesExplicitInterestsOnly() {
        val q = AssistedRanking.interestQuestion(profile)
        assertTrue("Core interests: LLMs." in q && "NLP" in q)
        assertFalse("Crypto" in q)
    }
}

class AiSettingsCodecTest {
    @Test fun roundTripsWithoutSecrets() {
        val s = AiSettings(listOf(cloud(caps = setOf(AiTask.Summarization, AiTask.QA)), lan()), mapOf(AiTask.Summarization to Route("c", "l")), AiPolicy(localOnly = true, dailyCloudRequests = 7))
        val json = AiSettingsCodec.encode(s)
        assertFalse("sk-" in json)
        assertEquals(s, AiSettingsCodec.decode(json))
    }

    @Test fun removingAProviderResetsItsRoutes() {
        val s = AiSettings(listOf(cloud(), lan()), mapOf(AiTask.Summarization to Route("c", "l"))).withoutProvider("c")
        assertEquals(Route(BUILTIN, "l"), s.route(AiTask.Summarization))
    }

    @Test fun routesMustMatchCapabilities() {
        val s = AiSettings(listOf(cloud()))
        assertFailsWith<AiException> { s.withRoute(AiTask.Embeddings, Route("c")) }
        assertFailsWith<AiException> { s.withRoute(AiTask.Summarization, Route("c", "c")) }
        assertEquals(Route("c"), s.withRoute(AiTask.Summarization, Route("c")).route(AiTask.Summarization))
    }

    @Test fun invalidStoredEntriesAreDropped() {
        val decoded = AiSettingsCodec.decode("""{"providers":[{"id":"x","name":"Bad","protocol":"openai","model":"m","endpoint":"http://api.example.com","capabilities":["qa"],"allowCloud":true}],"routes":{"qa":{"primary":"x"}}}""")
        assertTrue(decoded.providers.isEmpty())
        assertEquals(Route(), decoded.route(AiTask.QA))
        assertEquals(AiSettings(), AiSettingsCodec.decode("not json"))
    }
}
