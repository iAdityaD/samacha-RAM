package com.samacharam.core.feeds

import com.samacharam.core.FeedItem
import com.samacharam.core.ai.AiException
import com.samacharam.core.ai.AiTask
import com.samacharam.core.ai.BUILTIN
import com.samacharam.core.ai.Builtin
import com.samacharam.core.ai.ModelRouter
import com.samacharam.core.ai.TaskInput
import com.samacharam.core.ai.TaskOutput
import com.samacharam.core.canonicalUrl
import com.samacharam.core.db.FetchOutcome
import com.samacharam.core.db.Library
import com.samacharam.core.db.RetentionPolicy
import com.samacharam.core.db.SourceState
import com.samacharam.core.db.UpsertResult

/** Plain GET for public feeds. Implementations must reject non-public addresses and must not follow redirects. */
fun interface FeedTransport {
    fun get(url: String, headers: Map<String, String>): FeedResponse
}

/** [headers] use lowercase names. */
class FeedResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

data class SourceReport(val key: String, val name: String, val status: String, val newItems: Int, val error: String?)

data class RefreshReport(val sources: List<SourceReport>, val inserted: Int, val updated: Int, val duplicates: Int, val modelTagged: Int) {
    val failed: Int get() = sources.count { it.error != null }
}

/**
 * Refreshes due sources into the local library. A failed source keeps its existing articles and
 * backs off exponentially; a 304 costs almost no data; permanent redirects update the subscription.
 */
class Refresher(
    private val library: Library,
    private val transport: FeedTransport,
    private val clock: () -> Long,
    private val router: ModelRouter? = null,
    private val retention: RetentionPolicy = RetentionPolicy(),
) {
    companion object {
        const val MAX_BYTES = 3 * 1024 * 1024
        const val MAX_REDIRECTS = 3
        const val MAX_BACKOFF_MS = 24 * 60 * 60 * 1000L
        const val MODEL_TAG_LIMIT = 10
        const val USER_AGENT = "samacha-RAM/0.3 (personal feed reader; Android)"

        fun backoffMs(failures: Int, intervalMinutes: Int): Long {
            val base = intervalMinutes * 60_000L
            return minOf(MAX_BACKOFF_MS, base shl minOf(failures - 1, 10).coerceAtLeast(0))
        }
    }

    fun refresh(force: Boolean = false): RefreshReport {
        val now = clock()
        val due = library.sources().filter { it.enabled && (force || it.nextCheck == null || now >= it.nextCheck) }
        val topics = library.profile().topics.keys.toList()
        val reports = ArrayList<SourceReport>()
        val newIds = ArrayList<Long>()
        var inserted = 0; var updated = 0; var duplicates = 0
        for (source in due) {
            val result = fetch(source, now)
            if (result.items != null) {
                var added = 0
                for (item in result.items) {
                    val tagged = item.copy(topics = ruleTopics(item, topics, source.topic))
                    when (val r = runCatching { library.upsertArticle(source.key, tagged, now) }.getOrNull()) {
                        is UpsertResult.Inserted -> { inserted++; added++; newIds += r.id }
                        is UpsertResult.Updated -> updated++
                        is UpsertResult.Duplicate -> duplicates++
                        else -> Unit
                    }
                }
                reports += SourceReport(source.key, source.name, result.status, added, null)
            } else {
                reports += SourceReport(source.key, source.name, result.status, 0, result.error)
            }
        }
        val modelTagged = modelTopics(newIds, topics)
        library.applyRetention(now, retention)
        return RefreshReport(reports, inserted, updated, duplicates, modelTagged)
    }

    private class Fetched(val status: String, val items: List<FeedItem>?, val error: String?)

    private fun fetch(source: SourceState, now: Long): Fetched {
        var url = source.url
        var permanentTarget: String? = null
        repeat(MAX_REDIRECTS + 1) {
            if (!url.startsWith("https://")) return failure(source, now, "Feeds must use HTTPS.")
            val headers = buildMap {
                put("user-agent", USER_AGENT)
                put("accept", "application/rss+xml, application/atom+xml, application/feed+json, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.1")
                if (url == source.url) {
                    source.etag?.let { put("if-none-match", it) }
                    source.lastModified?.let { put("if-modified-since", it) }
                }
            }
            val response = try { transport.get(url, headers) } catch (e: Exception) {
                return failure(source, now, e.message?.takeIf { it.length < 200 && "://" !in it } ?: "Could not reach the feed.")
            }
            when (response.status) {
                200 -> {
                    if (response.body.size > MAX_BYTES) return failure(source, now, "Feed is larger than 3 MB.")
                    val parsed = try { FeedParser.parse(response.body.decodeToString(), url, source.type) } catch (e: Exception) {
                        return failure(source, now, "Could not read the feed: ${e.message?.take(120) ?: "invalid format"}")
                    }
                    if (permanentTarget != null) library.moveSource(source.id, permanentTarget!!)
                    library.recordFetch(
                        source.id,
                        FetchOutcome(
                            now, now + source.intervalMinutes * 60_000L, 0, true,
                            etag = response.headers["etag"], lastModified = response.headers["last-modified"], itemCount = parsed.items.size,
                        ),
                    )
                    return Fetched("updated", parsed.items, null)
                }
                304 -> {
                    library.recordFetch(source.id, FetchOutcome(now, now + source.intervalMinutes * 60_000L, 0, true))
                    return Fetched("unchanged", emptyList(), null)
                }
                301, 302, 303, 307, 308 -> {
                    val location = response.headers["location"] ?: return failure(source, now, "Redirect without a location.")
                    url = runCatching { canonicalUrl(FeedParser.resolve(location, url)) }.getOrElse { return failure(source, now, "Invalid redirect.") }
                    if (response.status == 301 || response.status == 308) { if (permanentTarget != null || it == 0) permanentTarget = url } else permanentTarget = null
                }
                429, 503 -> {
                    val retry = response.headers["retry-after"]?.trim()?.toLongOrNull()?.coerceIn(60, 86_400)
                    return failure(source, now, "The publisher asked to slow down (HTTP ${response.status}).", retry?.times(1000))
                }
                else -> return failure(source, now, "The publisher returned HTTP ${response.status}.")
            }
        }
        return failure(source, now, "Too many redirects.")
    }

    private fun failure(source: SourceState, now: Long, error: String, retryAfterMs: Long? = null): Fetched {
        val failures = source.failures + 1
        val wait = maxOf(retryAfterMs ?: 0, backoffMs(failures, source.intervalMinutes))
        library.recordFetch(source.id, FetchOutcome(now, now + wait, failures, false, error))
        return Fetched("failed", null, error)
    }

    /** Keyword rules over the owner's own topics; the source's topic when nothing matches. */
    private fun ruleTopics(item: FeedItem, topics: List<String>, fallback: String): List<String> {
        val labels = (Builtin.run(AiTask.Classification, TaskInput(item.title + "\n" + item.excerpt, labels = topics)) as TaskOutput.Labels).labels
        return labels.ifEmpty { listOf(fallback) }
    }

    /** Optional model tagging for a bounded batch of new items. Any failure keeps the rule-based topics. */
    private fun modelTopics(ids: List<Long>, topics: List<String>): Int {
        val r = router ?: return 0
        if (library.aiSettings().route(AiTask.Classification).primary == BUILTIN) return 0
        var tagged = 0
        for (id in ids.take(MODEL_TAG_LIMIT)) {
            val article = library.article(id) ?: continue
            val result = try { r.run(AiTask.Classification, TaskInput(article.title + "\n" + article.excerpt, labels = topics)) } catch (e: AiException) { break }
            val labels = (result.output as? TaskOutput.Labels)?.labels.orEmpty()
            if (labels.isNotEmpty() && result.providerId != BUILTIN) { library.setTopics(id, labels); tagged++ }
        }
        return tagged
    }
}
