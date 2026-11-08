package com.samacharam.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** JSON form of [AiSettings]. Contains no secrets: keys live only in the device [SecretStore]. */
object AiSettingsCodec {

    fun encode(s: AiSettings): String = buildJsonObject {
        put("version", 1)
        putJsonArray("providers") {
            s.providers.forEach { p ->
                add(buildJsonObject {
                    put("id", p.id); put("name", p.name); put("protocol", p.protocol.wire); put("model", p.model)
                    p.endpoint?.let { put("endpoint", it) }
                    putJsonArray("capabilities") { p.capabilities.sortedBy { it.ordinal }.forEach { add(it.wire) } }
                    put("allowCloud", p.allowCloud); put("maxInputChars", p.maxInputChars); put("maxOutputTokens", p.maxOutputTokens)
                    put("temperature", p.temperature); put("timeoutMs", p.timeoutMs)
                })
            }
        }
        putJsonObject("routes") {
            s.routes.entries.sortedBy { it.key.ordinal }.forEach { (task, r) ->
                putJsonObject(task.wire) { put("primary", r.primary); r.fallback?.let { put("fallback", it) } }
            }
        }
        putJsonObject("policy") {
            put("localOnly", s.policy.localOnly); put("allowCloudFallback", s.policy.allowCloudFallback)
            put("dailyCloudRequests", s.policy.dailyCloudRequests); put("assistedRanking", s.policy.assistedRanking)
        }
    }.toString()

    /** Invalid entries are dropped rather than guessed; routes to dropped providers revert to built-in. */
    fun decode(json: String): AiSettings {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return AiSettings()
        val providers = (root["providers"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            runCatching {
                ProviderConfig(
                    id = o.s("id")!!, name = o.s("name")!!, protocol = Protocol.fromWire(o.s("protocol")!!)!!, model = o.s("model")!!,
                    endpoint = o.s("endpoint"),
                    capabilities = (o["capabilities"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.let(AiTask::fromWire) }.toSet(),
                    allowCloud = o.b("allowCloud") ?: false, maxInputChars = o.i("maxInputChars") ?: 24_000,
                    maxOutputTokens = o.i("maxOutputTokens") ?: 2_048, temperature = o.d("temperature") ?: 0.0, timeoutMs = o.i("timeoutMs") ?: 90_000,
                ).also(::validateProvider)
            }.getOrNull()
        }
        val byId = providers.associateBy { it.id }
        fun usable(id: String?, task: AiTask) = id == BUILTIN || (id != null && byId[id]?.let { task in it.capabilities } == true)
        val routes = (root["routes"] as? JsonObject).orEmpty().mapNotNull { (name, value) ->
            val task = AiTask.fromWire(name) ?: return@mapNotNull null
            val o = value as? JsonObject ?: return@mapNotNull null
            val primary = o.s("primary").takeIf { usable(it, task) } ?: BUILTIN
            task to Route(primary, o.s("fallback").takeIf { it != primary && usable(it, task) })
        }.toMap()
        val policy = (root["policy"] as? JsonObject)?.let {
            AiPolicy(
                localOnly = it.b("localOnly") ?: false, allowCloudFallback = it.b("allowCloudFallback") ?: false,
                dailyCloudRequests = (it.i("dailyCloudRequests") ?: 100).coerceIn(0, 100_000), assistedRanking = it.b("assistedRanking") ?: false,
            )
        } ?: AiPolicy()
        return AiSettings(providers, routes, policy)
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
}

/** Applies an edit and checks the result is coherent: valid providers, routes only to providers declaring that task. */
fun AiSettings.withProvider(p: ProviderConfig): AiSettings {
    validateProvider(p)
    val updated = copy(providers = providers.filter { it.id != p.id } + p)
    return updated.copy(routes = updated.routes.mapValues { (task, r) -> updated.sanitize(task, r) })
}

fun AiSettings.withoutProvider(id: String): AiSettings {
    val updated = copy(providers = providers.filter { it.id != id })
    return updated.copy(routes = updated.routes.mapValues { (task, r) -> updated.sanitize(task, r) })
}

fun AiSettings.withRoute(task: AiTask, route: Route): AiSettings {
    for (id in listOfNotNull(route.primary, route.fallback)) {
        if (id == BUILTIN) continue
        val p = provider(id) ?: throw AiException("Unknown model.")
        if (task !in p.capabilities) throw AiException("${p.name} is not set up for ${task.wire}.")
    }
    if (route.fallback == route.primary) throw AiException("Choose a different model for the fallback.")
    return copy(routes = routes + (task to route))
}

private fun AiSettings.sanitize(task: AiTask, r: Route): Route {
    fun ok(id: String?) = id == BUILTIN || (id != null && provider(id)?.let { task in it.capabilities } == true)
    val primary = if (ok(r.primary)) r.primary else BUILTIN
    return Route(primary, r.fallback.takeIf { it != primary && ok(it) })
}
