package com.samacharam.core

import com.samacharam.core.db.Library
import com.samacharam.core.db.UpsertResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

data class ImportReport(
    val topics: Int, val sources: Int, val articles: Int, val saved: Int,
    val history: Int, val highlights: Int, val skipped: Int,
)

/**
 * Imports the JSON written by the Python host's "Export" (GET /api/export).
 * Unknown fields are ignored; records that fail validation are counted as skipped rather than guessed.
 */
object HostImport {

    fun import(json: String, library: Library, now: Long): ImportReport {
        val root = Json.parseToJsonElement(json) as? JsonObject ?: throw IllegalArgumentException("Export must be a JSON object.")
        var topics = 0; var sources = 0; var articles = 0; var saved = 0; var history = 0; var highlights = 0; var skipped = 0

        val profile = root["profile"].obj()
        profile?.get("topics").obj()?.forEach { (name, value) ->
            val layer = Layer.fromName(value.str() ?: "")
            if (layer != null && runCatching { library.setLayer(name, layer) }.isSuccess) topics++ else skipped++
        }
        profile?.get("budget").int()?.let { if (it in 1..120) library.budgetMinutes = it }
        profile?.get("tracking").bool()?.let { library.tracking = it }

        for (s in root["sources"].list()) {
            val o = s.obj() ?: continue
            val key = o["id"].str(); val name = o["name"].str(); val url = o["url"].str()
            if (key == null || name == null || url == null) { skipped++; continue }
            val ok = runCatching {
                library.upsertSource(key, name, url, ContentType.fromName(o["kind"].str() ?: "Blog"), o["topic"].str() ?: "Software", o["enabled"].bool() ?: true)
            }.isSuccess
            if (ok) sources++ else skipped++
        }

        val ids = HashMap<String, Long>()
        for (a in root["articles"].list()) {
            val o = a.obj() ?: continue
            val oldId = o["id"].str(); val url = o["url"].str(); val title = o["title"].str()
            if (oldId == null || url == null || title.isNullOrBlank()) { skipped++; continue }
            val published = parseIsoInstant(o["published"].str())
            val item = FeedItem(
                url = url, title = title, authors = o["authors"].str() ?: "", published = published,
                excerpt = o["excerpt"].str() ?: "", type = ContentType.fromName(o["type"].str() ?: "Article"),
                topics = o["topics"].list().mapNotNull { it.str() },
            )
            val sourceKey = o["source_id"].str()?.takeIf { it != "starter" && it != "shared" }
            val firstSeen = parseIsoInstant(o["fetched"].str()) ?: published ?: now
            val result = runCatching {
                library.upsertArticle(sourceKey, item, now, firstSeen, starter = o["starter"].bool() == true, detectDuplicates = false)
            }.getOrNull()
            val id = when (result) {
                is UpsertResult.Inserted -> result.id
                is UpsertResult.Updated -> result.id
                is UpsertResult.Unchanged -> result.id
                is UpsertResult.Duplicate -> result.of
                else -> null
            }
            if (id == null) { skipped++; continue }
            ids[oldId] = id; articles++
        }

        for (s in root["saved"].list()) {
            val o = s.obj() ?: continue
            val id = o["id"].str()?.let { ids[it] }
            if (id == null) { skipped++; continue }
            library.save(id, parseIsoInstant(o["created"].str()) ?: now, o["note"].str()?.takeIf { it.isNotEmpty() })
            saved++
        }

        val wasTracking = library.tracking
        library.tracking = true // import recorded history even if tracking is now paused; restore afterwards
        try {
            for (h in root["history"].list()) {
                val o = h.obj() ?: continue
                val id = o["article"].str()?.let { ids[it] }; val event = ReadingEvent.fromWire(o["kind"].str() ?: "")
                if (id == null || event == null) { skipped++; continue }
                library.recordEvent(id, event, parseIsoInstant(o["created"].str()) ?: now)
                history++
            }
        } finally {
            library.tracking = wasTracking
        }

        for (h in root["annotations"].list()) {
            val o = h.obj() ?: continue
            val ref = o["reference"].obj()
            val id = ref?.get("article_id").str()?.let { ids[it] }; val quote = ref?.get("quote").str()
            if (id == null || quote == null || quote.length < 8) { skipped++; continue }
            library.addHighlight(
                id, quote, parseIsoInstant(o["created"].str()) ?: now, o["note"].str() ?: "",
                ref?.get("document_version").str(), ref?.get("anchor_id").str(),
            )
            highlights++
        }
        return ImportReport(topics, sources, articles, saved, history, highlights, skipped)
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.list(): List<JsonElement> = (this as? JsonArray) ?: emptyList()
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.intOrNull
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.booleanOrNull
}
