package com.samacharam.core.sync

import com.samacharam.core.ContentType
import com.samacharam.core.FeedItem
import com.samacharam.core.Layer
import com.samacharam.core.db.Library
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The owner's own data as records with identities that are equal on every device:
 * topics by name, sources and saved items by canonical URL, highlights and missions by content hash.
 * Feed items, history, and API keys are deliberately not included.
 */
object SyncState {
    private fun id(vararg parts: String) = SyncCrypto.sha256(parts.joinToString("\u0000").encodeToByteArray()).toHex().take(24)

    fun snapshot(library: Library): Map<String, JsonObject> {
        val out = LinkedHashMap<String, JsonObject>()
        val profile = library.profile()
        profile.topics.forEach { (name, layer) -> out["topic:$name"] = buildJsonObject { put("name", name); put("layer", layer.name) } }
        out["budget"] = buildJsonObject { put("minutes", profile.budgetMinutes) }
        library.sources().forEach { s ->
            out["source:${s.url}"] = buildJsonObject {
                put("url", s.url); put("name", s.name); put("type", s.type.name); put("topic", s.topic); put("enabled", s.enabled)
            }
        }
        library.savedItems().forEach { item ->
            val a = item.article
            out["saved:${a.url}"] = buildJsonObject {
                put("url", a.url); put("title", a.title); put("authors", a.authors); a.published?.let { put("published", it) }
                put("type", a.type.name); putJsonArray("topics") { a.topics.sorted().forEach { add(JsonPrimitive(it)) } }
                put("excerpt", a.excerpt); put("savedAt", item.savedAt); put("note", item.note)
            }
        }
        library.highlightsWithUrls().forEach { (url, h) ->
            val title = library.articleIdForUrl(url)?.let { library.article(it)?.title } ?: url
            out["highlight:" + id(url, h.quote, h.created.toString())] = buildJsonObject {
                put("url", url); put("title", title); put("quote", h.quote); put("note", h.note); put("created", h.created)
                h.docVersion?.let { put("docVersion", it) }; h.anchor?.let { put("anchor", it) }
            }
        }
        val created = library.missionRecords().toMap()
        library.missions().forEach { m ->
            val c = created[m.id.toLong()] ?: 0L
            out["mission:" + id(m.title, c.toString())] = buildJsonObject {
                put("title", m.title); put("expires", m.expires); put("created", c)
                putJsonArray("topics") { m.topics.sorted().forEach { add(JsonPrimitive(it)) } }
            }
        }
        return out
    }

    private fun kindOrder(key: String) = when (key.substringBefore(':')) {
        "topic" -> 0; "budget" -> 1; "source" -> 2; "saved" -> 3; "highlight" -> 4; else -> 5
    }

    /**
     * Makes the library match [target] for every key in [changed]. Upserts run topics-first, deletions
     * run in reverse, so references stay valid. Returns the keys that could not be applied.
     */
    fun apply(library: Library, target: Map<String, SyncRecord>, changed: Collection<String>, now: Long): List<String> {
        val failed = ArrayList<String>()
        val (deletes, upserts) = changed.mapNotNull { target[it] }.partition { it.deleted }
        for (r in upserts.sortedBy { kindOrder(it.key) }) runCatching { upsert(library, r.key, r.payload!!, now) }.onFailure { failed += r.key }
        for (r in deletes.sortedByDescending { kindOrder(it.key) }) runCatching { delete(library, r.key) }.onFailure { failed += r.key }
        return failed
    }

    private fun upsert(library: Library, key: String, p: JsonObject, now: Long) {
        when (key.substringBefore(':')) {
            "topic" -> library.setLayer(p.s("name"), Layer.fromName(p.s("layer")) ?: Layer.Discovery)
            "budget" -> library.budgetMinutes = p.l("minutes").toInt()
            "source" -> {
                val url = p.s("url")
                val sourceKey = library.sourceKeyForUrl(url) ?: ("user-" + id(url).take(16))
                val known = library.profile().topics.keys
                library.upsertSource(sourceKey, p.s("name"), url, ContentType.fromName(p.s("type")), p.s("topic").takeIf { it in known } ?: "", p.b("enabled"))
            }
            "saved" -> {
                val known = library.profile().topics.keys
                val item = FeedItem(
                    p.s("url"), p.s("title"), p.s("authors"), (p["published"] as? JsonPrimitive)?.longOrNull, p.s("excerpt"),
                    ContentType.fromName(p.s("type")), p.list("topics").filter { it in known },
                )
                library.setSaved(library.ensureArticle(item, now), p.l("savedAt"), p.s("note"))
            }
            "highlight" -> {
                delete(library, key)
                val articleId = library.articleIdForUrl(p.s("url"))
                    ?: library.ensureArticle(FeedItem(p.s("url"), p.s("title"), "", null, "", ContentType.Article, emptyList()), now)
                library.addHighlight(articleId, p.s("quote"), p.l("created"), p.s("note"), p.sOrNull("docVersion"), p.sOrNull("anchor"))
            }
            "mission" -> {
                delete(library, key)
                val known = library.profile().topics.keys
                library.addMission(p.s("title"), p.list("topics").filter { it in known }.toSet(), p.s("expires"), p.l("created"))
            }
            else -> Unit // unknown kinds from a newer app version are kept in the sync file but not applied
        }
    }

    private fun delete(library: Library, key: String) {
        val rest = key.substringAfter(':')
        when (key.substringBefore(':')) {
            "topic" -> library.removeTopic(rest)
            "source" -> library.sourceKeyForUrl(rest)?.let(library::removeSource)
            "saved" -> library.articleIdForUrl(rest)?.let(library::unsave)
            "highlight" -> library.highlightsWithUrls().firstOrNull { (url, h) -> "highlight:" + id(url, h.quote, h.created.toString()) == key }
                ?.let { library.deleteHighlight(it.second.id) }
            "mission" -> {
                val created = library.missionRecords().toMap()
                library.missions().firstOrNull { "mission:" + id(it.title, (created[it.id.toLong()] ?: 0L).toString()) == key }
                    ?.let { library.deleteMission(it.id.toLong()) }
            }
        }
    }

    private fun JsonObject.sOrNull(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.s(k: String) = sOrNull(k) ?: ""
    private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.content == "true"
    private fun JsonObject.list(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

}
