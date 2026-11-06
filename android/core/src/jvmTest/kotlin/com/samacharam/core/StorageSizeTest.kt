package com.samacharam.core

import com.samacharam.core.db.Library
import com.samacharam.core.db.UpsertResult
import com.samacharam.core.db.queryLong
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Measures, rather than assumes, storage for one year of heavy use:
 * 10,000 feed items over 365 days, 500 saved with notes and two highlights, 20,000 reading events.
 * Synthetic text uses a Zipf-distributed vocabulary so it compresses roughly like prose, not like repeated filler.
 */
class StorageSizeTest {
    private val dir: File = Files.createTempDirectory("samacha-size").toFile()
    private val now = 1_800_000_000_000L
    private val random = Random(42)

    @AfterTest fun cleanup() = dir.deleteRecursively().let { }

    private val vocabulary: List<String> = run {
        val syllables = listOf("ra", "te", "mo", "lin", "qu", "an", "ti", "za", "der", "ne", "ur", "al", "ex", "per", "ion", "gra", "ph", "or", "ic", "sta")
        val r = Random(7)
        List(4_000) { buildString { repeat(1 + r.nextInt(4)) { append(syllables[r.nextInt(syllables.size)]) } } }
    }
    private val cumulative: DoubleArray = run {
        val weights = DoubleArray(vocabulary.size) { 1.0 / (it + 1) }
        val total = weights.sum(); var acc = 0.0
        DoubleArray(weights.size) { acc += weights[it] / total; acc }
    }

    private fun word(): String {
        val x = random.nextDouble()
        var lo = 0; var hi = cumulative.size - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (cumulative[mid] < x) lo = mid + 1 else hi = mid }
        return vocabulary[lo]
    }

    private fun prose(words: Int): String = buildString {
        var inSentence = 0
        repeat(words) {
            val w = word()
            append(if (inSentence == 0) w.replaceFirstChar { it.uppercase() } else w)
            inSentence++
            if (inSentence > 8 + random.nextInt(12)) { append(". "); inSentence = 0 } else append(' ')
        }
    }.trim()

    private fun fileBytes(db: File): Long =
        listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).filter { it.exists() }.sumOf { it.length() }

    private fun mb(bytes: Long) = "%.2f MB".format(bytes / 1_048_576.0)

    @Test fun oneYearOfHeavyUseStaysSmall() {
        val topics = listOf("LLMs", "NLP", "Hardware", "Software", "AI audio", "AI video")
        val sources = (1..12).map { "source-$it" }

        val dbFile = File(dir, "library.db")
        val driver = JdbcDriver(dbFile)
        val library = Library(driver).also { it.open() }
        sources.forEachIndexed { i, key -> library.upsertSource(key, "Source $i", "https://feeds.example.com/$key.xml", ContentType.Blog, topics[i % topics.size]) }

        val legacyFile = File(dir, "legacy.sqlite3")
        val legacy = JdbcDriver(legacyFile)
        legacy.execute("PRAGMA journal_mode=WAL")
        legacy.execute("CREATE TABLE articles(id TEXT PRIMARY KEY, body TEXT NOT NULL)")

        val ids = ArrayList<Long>()
        var textBytes = 0L
        driver.transaction {
            legacy.transaction {
                for (n in 0 until 10_000) {
                    val firstSeen = now - (n.toLong() * 365 * DAY_MS) / 10_000
                    val title = prose(6 + random.nextInt(8)).removeSuffix(".") + " $n"
                    val excerpt = prose(120 + random.nextInt(140))
                    textBytes += title.length + excerpt.length
                    val itemTopics = listOf(topics[random.nextInt(topics.size)], topics[random.nextInt(topics.size)]).distinct()
                    val source = sources[random.nextInt(sources.size)]
                    val url = "https://www.example-publisher.com/blog/2026/${title.lowercase().replace(' ', '-').take(60)}/"
                    val result = library.upsertArticle(
                        source, FeedItem(url, title, "Author ${random.nextInt(500)}", firstSeen - 3_600_000, excerpt, ContentType.Blog, itemTopics),
                        now, firstSeen, detectDuplicates = false,
                    )
                    ids += (result as UpsertResult.Inserted).id

                    // The same record as the Python host stores it (server/feeds.py parse_feed), as JSON text.
                    val json = """{"id":"${"%020x".format(n)}","title":"$title","url":"$url","source_id":"$source","source":"Source","type":"Blog","topics":[${itemTopics.joinToString(",") { "\"$it\"" }}],"excerpt":"$excerpt","authors":"Author 1","published":"2026-10-18T10:00:00+00:00","fetched":"2026-10-18T10:00:00.123456+00:00","version":"0123456789abcdef","evidence":"Not assessed","access":"Feed excerpt only","publication":"Publisher feed","starter":false,"minutes":1,"color":"#719485"}"""
                    legacy.execute("INSERT INTO articles VALUES(?,?)", listOf("%020x".format(n), json))
                }
            }
        }

        driver.transaction {
            for (id in ids.shuffled(random).take(500)) {
                library.save(id, now, prose(250 + random.nextInt(200)))
                repeat(2) { library.addHighlight(id, prose(25), now, prose(30)) }
            }
            repeat(20_000) {
                library.recordEvent(ids[random.nextInt(ids.size)], ReadingEvent.entries[random.nextInt(ReadingEvent.entries.size)], now)
            }
        }

        driver.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        legacy.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        val fullYear = fileBytes(dbFile)
        val legacyBytes = fileBytes(legacyFile)

        val report = library.applyRetention(now)
        driver.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        val afterRetention = fileBytes(dbFile)
        val stats = library.stats()
        val pages = "pages=${driver.queryLong("PRAGMA page_count")} free=${driver.queryLong("PRAGMA freelist_count")}"
        val ftsBytes = driver.queryLong("SELECT sum(length(block)) FROM article_fts_data")
        driver.execute("VACUUM"); driver.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        val vacuumed = fileBytes(dbFile)

        val summary = """
            |Storage measurement (synthetic, seed 42)
            |  raw title+excerpt text, 10,000 items:          ${mb(textBytes)}
            |  Python host format (JSON in SQLite), articles: ${mb(legacyBytes)}
            |  New schema, all 10,000 kept + saves/history:   ${mb(fullYear)}
            |  New schema after retention (steady state):     ${mb(afterRetention)}
            |    articles kept=${stats.articles} saved=${stats.saved} highlights=${stats.highlights} history=${stats.history}
            |    $pages, FTS index ${mb(ftsBytes)}, after full VACUUM ${mb(vacuumed)}
            |    excerpts dropped=${report.excerptsDropped} articles deleted=${report.articlesDeleted}
        """.trimMargin()
        println(summary)
        File("build/storage-report.txt").apply { parentFile.mkdirs() }.writeText(summary + "\n")

        legacy.close(); driver.close()
        assertTrue(fullYear < legacyBytes, "structured + compressed storage should beat JSON documents")
        assertTrue(afterRetention < 15L * 1_048_576, "steady state should stay under 15 MB")
    }
}
