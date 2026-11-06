package com.samacharam.core.db

import com.samacharam.core.Article
import com.samacharam.core.ContentType
import com.samacharam.core.DAY_MS
import com.samacharam.core.FeedItem
import com.samacharam.core.Layer
import com.samacharam.core.Profile
import com.samacharam.core.ReadingEvent
import com.samacharam.core.TextCodec
import com.samacharam.core.canonicalUrl
import com.samacharam.core.estimatedMinutes
import com.samacharam.core.fnv64
import com.samacharam.core.isNearDuplicate
import com.samacharam.core.simHash
import com.samacharam.core.titleTokens
import com.samacharam.core.urlHash
import com.samacharam.core.ai.AiSettings
import com.samacharam.core.ai.AiSettingsCodec
import com.samacharam.core.ai.UsageCounter

sealed class UpsertResult {
    data class Inserted(val id: Long) : UpsertResult()
    data class Updated(val id: Long) : UpsertResult()
    data class Unchanged(val id: Long) : UpsertResult()
    /** A syndicated copy of an existing item; not stored again. */
    data class Duplicate(val of: Long) : UpsertResult()
    /** Previously pruned by retention; not re-added as new. */
    data object Skipped : UpsertResult()
}

data class RetentionPolicy(val keepExcerptDays: Int = 30, val deleteAfterDays: Int = 90, val rememberPrunedDays: Int = 365)

data class RetentionReport(val excerptsDropped: Int, val articlesDeleted: Int)

data class StorageStats(val bytes: Long, val freeBytes: Long, val articles: Long, val saved: Long, val highlights: Long, val history: Long)

data class SavedItem(val article: Article, val savedAt: Long, val note: String, val highlights: List<Highlight>)

data class Highlight(val id: Long, val quote: String, val note: String, val docVersion: String?, val anchor: String?, val created: Long)

/** The phone's local library: the working copy in every storage mode. */
class Library(private val db: SqlDriver) {

    fun open() {
        val isNew = db.queryLong("SELECT count(*) FROM sqlite_master") == 0L
        if (isNew) db.execute(Schema.CREATE_PRAGMA)
        Schema.connectionPragmas.forEach { db.execute(it) }
        if (isNew) {
            db.transaction {
                Schema.v1.forEach { db.execute(it) }
                setMeta("schema_version", Schema.VERSION.toString())
            }
        }
        var version = meta("schema_version")?.toInt() ?: throw IllegalStateException("Unrecognized database.")
        require(version <= Schema.LATEST) { "Database was created by a newer app version." }
        while (version < Schema.LATEST) {
            val steps = Schema.migrations[version - Schema.VERSION]
            db.transaction {
                steps.forEach { db.execute(it) }
                setMeta("schema_version", (version + 1).toString())
            }
            version++
        }
    }

    // ---- settings ----

    fun meta(key: String): String? = db.query("SELECT value FROM meta WHERE key=?", listOf(key)) { it.string(0) }.firstOrNull()

    fun setMeta(key: String, value: String) = db.execute("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)", listOf(key, value))

    var tracking: Boolean
        get() = meta("tracking") != "0"
        set(value) = setMeta("tracking", if (value) "1" else "0")

    var budgetMinutes: Int
        get() = meta("budget")?.toIntOrNull() ?: 15
        set(value) { require(value in 1..120) { "Reading budget must be 1–120 minutes." }; setMeta("budget", value.toString()) }

    // ---- model connections (no secrets here; keys live in the device SecretStore) ----

    fun aiSettings(): AiSettings = meta("ai.settings")?.let(AiSettingsCodec::decode) ?: AiSettings()

    fun saveAiSettings(settings: AiSettings) = setMeta("ai.settings", AiSettingsCodec.encode(settings))

    /** Daily cloud request counter stored with the library, so restarts cannot reset the budget. */
    val cloudUsage: UsageCounter = object : UsageCounter {
        override fun tryConsume(day: String, limit: Int): Boolean = db.transaction {
            val stored = meta("ai.usage")?.split('|')
            val used = if (stored?.getOrNull(0) == day) stored.getOrNull(1)?.toIntOrNull() ?: 0 else 0
            if (used >= limit) return@transaction false
            setMeta("ai.usage", "$day|${used + 1}")
            true
        }
    }

    // ---- topics ----

    fun topicId(name: String): Long {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 80) { "Topic names need 1–80 characters." }
        db.execute("INSERT OR IGNORE INTO topic(name) VALUES(?)", listOf(clean))
        return db.queryLong("SELECT id FROM topic WHERE name=?", listOf(clean))
    }

    fun setLayer(topic: String, layer: Layer) {
        val id = topicId(topic)
        db.execute("UPDATE topic SET layer=? WHERE id=?", listOf(layer.code, id))
    }

    fun profile(): Profile = Profile(
        topics = db.query("SELECT name, layer FROM topic") { it.string(0)!! to Layer.fromCode(it.long(1)!!.toInt()) }.toMap(),
        budgetMinutes = budgetMinutes,
        tracking = tracking,
    )

    // ---- sources ----

    fun upsertSource(key: String, name: String, url: String, type: ContentType, topic: String, enabled: Boolean = true): Long {
        val canonical = canonicalUrl(url)
        val topicId = topicId(topic)
        db.execute(
            """INSERT INTO source(key,name,url,type,topic_id,enabled) VALUES(?,?,?,?,?,?)
               ON CONFLICT(key) DO UPDATE SET name=excluded.name, url=excluded.url, type=excluded.type,
               topic_id=excluded.topic_id, enabled=excluded.enabled""",
            listOf(key, name.take(100), canonical, type.code, topicId, enabled),
        )
        return db.queryLong("SELECT id FROM source WHERE key=?", listOf(key))
    }

    fun sources(): List<SourceState> = db.query(
        """SELECT s.id, s.key, s.name, s.url, s.type, COALESCE(t.name,''), s.enabled, s.etag, s.last_modified, s.failures,
                  s.next_check, s.last_success, s.last_checked, s.last_error, s.interval_minutes, s.item_count
           FROM source s LEFT JOIN topic t ON t.id=s.topic_id ORDER BY s.name""",
    ) {
        SourceState(
            id = it.long(0)!!, key = it.string(1)!!, name = it.string(2)!!, url = it.string(3)!!, type = ContentType.fromCode(it.long(4)!!.toInt()),
            topic = it.string(5)!!, enabled = it.long(6) == 1L, etag = it.string(7), lastModified = it.string(8), failures = it.long(9)!!.toInt(),
            nextCheck = it.long(10), lastSuccess = it.long(11), lastChecked = it.long(12), lastError = it.string(13),
            intervalMinutes = it.long(14)!!.toInt(), itemCount = it.long(15)!!.toInt(),
        )
    }

    fun setSourceEnabled(key: String, enabled: Boolean) =
        db.execute("UPDATE source SET enabled=?, next_check=NULL WHERE key=?", listOf(enabled, key))

    /** Stores the outcome of one fetch attempt. Existing articles are never removed by a failed fetch. */
    fun recordFetch(id: Long, outcome: FetchOutcome) = db.execute(
        """UPDATE source SET last_checked=?, next_check=?, failures=?, last_error=?,
               etag=COALESCE(?, etag), last_modified=COALESCE(?, last_modified),
               last_success=CASE WHEN ? THEN ? ELSE last_success END,
               item_count=CASE WHEN ? >= 0 THEN ? ELSE item_count END
           WHERE id=?""",
        listOf(
            outcome.checkedAt, outcome.nextCheck, outcome.failures, outcome.error, outcome.etag, outcome.lastModified,
            outcome.success, outcome.checkedAt, outcome.itemCount, outcome.itemCount, id,
        ),
    )

    /** Follows a permanent redirect. Ignored when another subscription already uses the new URL. */
    fun moveSource(id: Long, newUrl: String): Boolean {
        val canonical = canonicalUrl(newUrl)
        if (db.queryLong("SELECT count(*) FROM source WHERE url=? AND id<>?", listOf(canonical, id)) > 0) return false
        db.execute("UPDATE source SET url=?, etag=NULL, last_modified=NULL WHERE id=?", listOf(canonical, id))
        return true
    }

    /** First-run defaults: a small set of public research feeds and the default interest layers. No sample articles. */
    fun seedDefaults() = db.transaction {
        if (db.queryLong("SELECT count(*) FROM topic") == 0L) {
            DEFAULT_LAYERS.forEach { (topic, layer) -> setLayer(topic, layer) }
        }
        if (db.queryLong("SELECT count(*) FROM source") == 0L) {
            DEFAULT_SOURCES.forEach { s -> upsertSource(s.key, s.name, s.url, s.type, s.topic) }
        }
    }

    // ---- articles ----

    fun upsertArticle(
        sourceKey: String?,
        item: FeedItem,
        now: Long,
        firstSeen: Long = now,
        starter: Boolean = false,
        detectDuplicates: Boolean = true,
    ): UpsertResult = db.transaction {
        val url = canonicalUrl(item.url)
        val hash = urlHash(url)
        val title = item.title.trim().take(500)
        require(title.isNotEmpty()) { "Articles need a title." }
        val excerpt = item.excerpt.take(16_000)
        val version = fnv64(title + "\u0000" + excerpt)
        val existing = db.query("SELECT id, version, flags FROM article WHERE url_hash=?", listOf(hash)) {
            Triple(it.long(0)!!, it.long(1)!!, it.long(2)!!)
        }.firstOrNull()

        if (existing != null) {
            val (id, oldVersion, flags) = existing
            if (oldVersion == version) return@transaction UpsertResult.Unchanged(id)
            db.execute(
                """UPDATE article SET title=?, authors=?, published=?, excerpt=?, version=?, title_hash=?, minutes=?, flags=? WHERE id=?""",
                listOf(
                    title, item.authors.take(500), item.published, encodeOrNull(excerpt), version, simHash(titleTokens(title)),
                    estimatedMinutes(excerpt), (flags or ArticleFlags.CHANGED) and ArticleFlags.EXCERPT_PRUNED.inv(), id,
                ),
            )
            replaceTopics(id, item.topics)
            reindex(id, title, excerpt)
            return@transaction UpsertResult.Updated(id)
        }

        if (db.queryLong("SELECT count(*) FROM seen_url WHERE url_hash=?", listOf(hash)) > 0) return@transaction UpsertResult.Skipped

        if (detectDuplicates && item.published != null) {
            val window = 3 * DAY_MS
            val candidates = db.query(
                "SELECT id, title, published FROM article WHERE published BETWEEN ? AND ? LIMIT 300",
                listOf(item.published - window, item.published + window),
            ) { Triple(it.long(0)!!, it.string(1)!!, it.long(2)) }
            candidates.firstOrNull { isNearDuplicate(title, item.published, it.second, it.third) }
                ?.let { return@transaction UpsertResult.Duplicate(it.first) }
        }

        val sourceId = sourceKey?.let { key -> db.query("SELECT id FROM source WHERE key=?", listOf(key)) { it.long(0) }.firstOrNull() }
        db.execute(
            """INSERT INTO article(url_hash,url,source_id,title,authors,published,first_seen,type,minutes,version,title_hash,excerpt,flags)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            listOf(
                hash, url, sourceId, title, item.authors.take(500), item.published, firstSeen, item.type.code,
                estimatedMinutes(excerpt), version, simHash(titleTokens(title)), encodeOrNull(excerpt),
                if (starter) ArticleFlags.STARTER else 0L,
            ),
        )
        val id = db.queryLong("SELECT id FROM article WHERE url_hash=?", listOf(hash))
        replaceTopics(id, item.topics)
        db.execute("INSERT INTO article_fts(rowid,title,body) VALUES(?,?,?)", listOf(id, title, excerpt))
        UpsertResult.Inserted(id)
    }

    private fun encodeOrNull(text: String): ByteArray? = if (text.isEmpty()) null else TextCodec.encode(text)

    /** Replaces an item's topics (e.g. after model classification), without touching its content version. */
    fun setTopics(id: Long, topics: List<String>) = db.transaction { replaceTopics(id, topics) }

    private fun replaceTopics(id: Long, topics: List<String>) {
        db.execute("DELETE FROM article_topic WHERE article_id=?", listOf(id))
        for (topic in topics.distinct().take(12)) {
            db.execute("INSERT OR IGNORE INTO article_topic(article_id,topic_id) VALUES(?,?)", listOf(id, topicId(topic)))
        }
    }

    private fun reindex(id: Long, title: String, body: String) {
        db.execute("DELETE FROM article_fts WHERE rowid=?", listOf(id))
        db.execute("INSERT INTO article_fts(rowid,title,body) VALUES(?,?,?)", listOf(id, title, body))
    }

    private fun topicsById(): Map<Long, List<String>> {
        val result = HashMap<Long, MutableList<String>>()
        db.query("SELECT at.article_id, t.name FROM article_topic at JOIN topic t ON t.id=at.topic_id") { it.long(0)!! to it.string(1)!! }
            .forEach { (id, name) -> result.getOrPut(id) { mutableListOf() } += name }
        return result
    }

    private val articleColumns =
        "a.id, a.url, COALESCE(s.key,''), a.title, COALESCE(a.authors,''), a.published, a.first_seen, a.type, a.excerpt, a.minutes, a.flags"

    private fun readArticle(row: SqlRow, topics: Map<Long, List<String>>): Article {
        val id = row.long(0)!!
        return Article(
            id = id, url = row.string(1)!!, sourceKey = row.string(2)!!, title = row.string(3)!!, authors = row.string(4)!!,
            published = row.long(5), firstSeen = row.long(6)!!, type = ContentType.fromCode(row.long(7)!!.toInt()),
            topics = topics[id].orEmpty(), excerpt = row.bytes(8)?.let(TextCodec::decode) ?: "",
            minutes = row.long(9)!!.toInt(), starter = (row.long(10)!! and ArticleFlags.STARTER) != 0L,
        )
    }

    fun articles(limit: Int = 2_000): List<Article> {
        val topics = topicsById()
        return db.query(
            "SELECT $articleColumns FROM article a LEFT JOIN source s ON s.id=a.source_id ORDER BY a.first_seen DESC LIMIT ?",
            listOf(limit),
        ) { readArticle(it, topics) }
    }

    fun article(id: Long): Article? {
        val topics = topicsById()
        return db.query("SELECT $articleColumns FROM article a LEFT JOIN source s ON s.id=a.source_id WHERE a.id=?", listOf(id)) {
            readArticle(it, topics)
        }.firstOrNull()
    }

    /** Full-text search. Every word must match (prefix match on each); punctuation cannot inject FTS syntax. */
    fun search(query: String, limit: Int = 20): List<Long> {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.take(12)
        if (terms.isEmpty()) return emptyList()
        val match = terms.joinToString(" ") { "\"$it\"*" }
        return db.query(
            "SELECT rowid FROM article_fts WHERE article_fts MATCH ? ORDER BY bm25(article_fts, 4.0, 1.0) LIMIT ?",
            listOf(match, limit),
        ) { it.long(0)!! }
    }

    // ---- library ----

    fun save(articleId: Long, now: Long, note: String? = null) {
        require(db.queryLong("SELECT count(*) FROM article WHERE id=?", listOf(articleId)) == 1L) { "Article not found." }
        db.execute("INSERT OR IGNORE INTO saved(article_id,saved_at) VALUES(?,?)", listOf(articleId, now))
        if (note != null) db.execute("UPDATE saved SET note=? WHERE article_id=?", listOf(encodeOrNull(note.take(20_000)), articleId))
    }

    fun unsave(articleId: Long) = db.execute("DELETE FROM saved WHERE article_id=?", listOf(articleId))

    fun addHighlight(articleId: Long, quote: String, now: Long, note: String = "", docVersion: String? = null, anchor: String? = null): Long {
        require(quote.length >= 8) { "Highlights need at least 8 characters." }
        db.execute(
            "INSERT INTO highlight(article_id,quote,note,doc_version,anchor,created) VALUES(?,?,?,?,?,?)",
            listOf(articleId, quote.take(3_000), encodeOrNull(note.take(10_000)), docVersion, anchor, now),
        )
        return db.queryLong("SELECT max(id) FROM highlight WHERE article_id=?", listOf(articleId))
    }

    fun savedItems(): List<SavedItem> {
        val highlights = db.query("SELECT article_id, id, quote, note, doc_version, anchor, created FROM highlight ORDER BY id") {
            it.long(0)!! to Highlight(it.long(1)!!, it.string(2)!!, it.bytes(3)?.let(TextCodec::decode) ?: "", it.string(4), it.string(5), it.long(6)!!)
        }.groupBy({ it.first }, { it.second })
        val topics = topicsById()
        return db.query(
            "SELECT $articleColumns, v.saved_at, v.note FROM saved v JOIN article a ON a.id=v.article_id LEFT JOIN source s ON s.id=a.source_id ORDER BY v.saved_at DESC",
        ) { row ->
            val article = readArticle(row, topics)
            SavedItem(article, row.long(11)!!, row.bytes(12)?.let(TextCodec::decode) ?: "", highlights[article.id].orEmpty())
        }
    }

    // ---- history ----

    /** Records nothing while tracking is paused. */
    fun recordEvent(articleId: Long, event: ReadingEvent, now: Long): Boolean {
        if (!tracking) return false
        val version = db.query("SELECT version FROM article WHERE id=?", listOf(articleId)) { it.long(0) }.firstOrNull()
            ?: throw IllegalArgumentException("Article not found.")
        db.execute("INSERT INTO history(article_id,kind,at,version) VALUES(?,?,?,?)", listOf(articleId, event.code, now, version))
        return true
    }

    fun readIds(): Set<Long> =
        db.query("SELECT DISTINCT article_id FROM history WHERE kind=?", listOf(ReadingEvent.MarkRead.code)) { it.long(0)!! }.toSet()

    fun clearHistory() = db.execute("DELETE FROM history")

    // ---- retention ----

    private val unprotected =
        "NOT EXISTS(SELECT 1 FROM saved v WHERE v.article_id=a.id) AND NOT EXISTS(SELECT 1 FROM highlight h WHERE h.article_id=a.id)"

    /** Drops old excerpts, then old rows, for items the owner never saved or highlighted. */
    fun applyRetention(now: Long, policy: RetentionPolicy = RetentionPolicy()): RetentionReport {
        val report = db.transaction {
            val dropBefore = now - policy.keepExcerptDays * DAY_MS
            val deleteBefore = now - policy.deleteAfterDays * DAY_MS
            // Items about to be deleted are not trimmed first.
            val toTrim = db.query(
                "SELECT a.id, a.title FROM article a WHERE a.first_seen < ? AND a.first_seen >= ? AND a.excerpt IS NOT NULL AND $unprotected",
                listOf(dropBefore, deleteBefore),
            ) { it.long(0)!! to it.string(1)!! }
            for ((id, title) in toTrim) {
                reindex(id, title, "")
                db.execute("UPDATE article SET excerpt=NULL, flags=flags|? WHERE id=?", listOf(ArticleFlags.EXCERPT_PRUNED, id))
            }

            val toDelete = db.query(
                "SELECT a.id, a.url_hash FROM article a WHERE a.first_seen < ? AND $unprotected",
                listOf(deleteBefore),
            ) { it.long(0)!! to it.long(1)!! }
            for ((id, hash) in toDelete) {
                db.execute("INSERT OR REPLACE INTO seen_url(url_hash,last_seen) VALUES(?,?)", listOf(hash, now))
                db.execute("DELETE FROM article_fts WHERE rowid=?", listOf(id))
                db.execute("DELETE FROM article WHERE id=?", listOf(id))
            }
            db.execute("DELETE FROM seen_url WHERE last_seen < ?", listOf(now - policy.rememberPrunedDays * DAY_MS))
            RetentionReport(toTrim.size, toDelete.size)
        }
        if (report.excerptsDropped + report.articlesDeleted > 0) compact()
        return report
    }

    /**
     * Applies FTS deletions and returns free pages to the filesystem without a full VACUUM
     * (which would rewrite the whole file and need as much free space again).
     * Drivers differ in how far a single execution of incremental_vacuum steps, so it is
     * repeated in bounded batches until the free list is empty.
     */
    fun compact() {
        db.execute("INSERT INTO article_fts(article_fts) VALUES('optimize')")
        var previous = Long.MAX_VALUE
        repeat(10_000) {
            val free = db.queryLong("PRAGMA freelist_count")
            if (free == 0L || free >= previous) return
            previous = free
            db.execute("PRAGMA incremental_vacuum(256)")
        }
    }

    fun stats(): StorageStats {
        val pageSize = db.queryLong("PRAGMA page_size")
        return StorageStats(
            bytes = db.queryLong("PRAGMA page_count") * pageSize,
            freeBytes = db.queryLong("PRAGMA freelist_count") * pageSize,
            articles = db.queryLong("SELECT count(*) FROM article"),
            saved = db.queryLong("SELECT count(*) FROM saved"),
            highlights = db.queryLong("SELECT count(*) FROM highlight"),
            history = db.queryLong("SELECT count(*) FROM history"),
        )
    }
}

data class SourceState(
    val id: Long, val key: String, val name: String, val url: String, val type: ContentType, val topic: String, val enabled: Boolean,
    val etag: String?, val lastModified: String?, val failures: Int, val nextCheck: Long?, val lastSuccess: Long?,
    val lastChecked: Long?, val lastError: String?, val intervalMinutes: Int, val itemCount: Int,
)

data class FetchOutcome(
    val checkedAt: Long, val nextCheck: Long, val failures: Int, val success: Boolean, val error: String? = null,
    val etag: String? = null, val lastModified: String? = null, val itemCount: Int = -1,
)

data class DefaultSource(val key: String, val name: String, val url: String, val type: ContentType, val topic: String)

val DEFAULT_SOURCES = listOf(
    DefaultSource("arxiv-ai", "arXiv · AI", "https://rss.arxiv.org/rss/cs.AI", ContentType.Paper, "LLMs"),
    DefaultSource("arxiv-cl", "arXiv · Language", "https://rss.arxiv.org/rss/cs.CL", ContentType.Paper, "NLP"),
    DefaultSource("hf", "Hugging Face", "https://huggingface.co/blog/feed.xml", ContentType.Blog, "LLMs"),
    DefaultSource("pytorch", "PyTorch", "https://pytorch.org/blog/feed.xml", ContentType.Blog, "Software"),
    DefaultSource("google", "Google Research", "https://research.google/blog/rss/", ContentType.Blog, "LLMs"),
)

val DEFAULT_LAYERS = listOf(
    "LLMs" to Layer.Core, "NLP" to Layer.Near, "Software" to Layer.Near,
    "Hardware" to Layer.Adjacent, "AI audio" to Layer.Adjacent, "AI video" to Layer.Discovery,
)
