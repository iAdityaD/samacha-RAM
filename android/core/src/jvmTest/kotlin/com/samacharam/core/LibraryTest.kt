package com.samacharam.core

import com.samacharam.core.db.Library
import com.samacharam.core.db.UpsertResult
import com.samacharam.core.db.queryLong
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibraryTest {
    private val dir: File = Files.createTempDirectory("samacha-core").toFile()
    private val driver = JdbcDriver(File(dir, "library.db"))
    private val library = Library(driver).also { it.open() }
    private val now = 1_800_000_000_000L

    @AfterTest fun cleanup() { driver.close(); dir.deleteRecursively() }

    private fun item(n: Int, title: String = "Paper number $n about retrieval models", excerpt: String = "An excerpt for item $n describing retrieval.", published: Long? = now - n * DAY_MS) =
        FeedItem("https://example.com/p/$n/", title, "Author $n", published, excerpt, ContentType.Paper, listOf("LLMs"))

    @Test fun schemaUsesIncrementalVacuumAndForeignKeys() {
        assertEquals(2L, driver.queryLong("PRAGMA auto_vacuum"))
        assertEquals(1L, driver.queryLong("PRAGMA foreign_keys"))
        assertEquals("2", library.meta("schema_version"))
    }

    @Test fun reopeningKeepsData() {
        library.upsertArticle(null, item(1), now)
        val again = Library(driver).also { it.open() }
        assertEquals(1, again.articles().size)
    }

    @Test fun upsertDetectsUnchangedAndChangedContent() {
        val first = assertIs<UpsertResult.Inserted>(library.upsertArticle(null, item(1), now))
        assertIs<UpsertResult.Unchanged>(library.upsertArticle(null, item(1), now))
        val changed = library.upsertArticle(null, item(1, excerpt = "Corrected excerpt with a new figure."), now)
        assertEquals(UpsertResult.Updated(first.id), changed)
        assertEquals("Corrected excerpt with a new figure.", library.article(first.id)!!.excerpt)
        assertEquals("https://example.com/p/1/", library.article(first.id)!!.url) // trailing slash preserved
    }

    @Test fun syndicatedCopyIsNotStoredTwice() {
        val original = assertIs<UpsertResult.Inserted>(
            library.upsertArticle(null, item(1, title = "Acme releases Falcon-9B open speech recognition model", published = now), now)
        )
        val copy = FeedItem("https://mirror.example.org/acme-falcon", "Acme Releases Falcon-9B Open Speech Recognition Model", "", now + 3_600_000, "", ContentType.News, listOf("AI audio"))
        assertEquals(UpsertResult.Duplicate(original.id), library.upsertArticle(null, copy, now))
        assertEquals(1, library.articles().size)
    }

    @Test fun fullTextSearchMatchesTitleAndBodyWithPrefixes() {
        val a = assertIs<UpsertResult.Inserted>(library.upsertArticle(null, item(1, title = "Low-latency speech inference on phones", excerpt = "Quantized encoders reduce memory."), now))
        library.upsertArticle(null, item(2, title = "Database indexing for analytics", excerpt = "Columnar storage."), now)
        assertEquals(listOf(a.id), library.search("speech"))
        assertEquals(listOf(a.id), library.search("quantiz"))
        assertEquals(listOf(a.id), library.search("SPEECH phones"))
        assertEquals(emptyList(), library.search("speech columnar"))
        assertEquals(emptyList(), library.search("\" OR * NEAR( ) -- "))
    }

    @Test fun retentionTrimsThenDeletesUnprotectedItemsOnly() {
        val old = assertIs<UpsertResult.Inserted>(library.upsertArticle(null, item(1, excerpt = "Unique sentinel wording."), now, firstSeen = now - 40 * DAY_MS))
        val ancient = assertIs<UpsertResult.Inserted>(library.upsertArticle(null, item(2), now, firstSeen = now - 100 * DAY_MS))
        val saved = assertIs<UpsertResult.Inserted>(library.upsertArticle(null, item(3), now, firstSeen = now - 100 * DAY_MS))
        val highlighted = assertIs<UpsertResult.Inserted>(library.upsertArticle(null, item(4), now, firstSeen = now - 100 * DAY_MS))
        val fresh = assertIs<UpsertResult.Inserted>(library.upsertArticle(null, item(5), now, firstSeen = now - DAY_MS))
        library.save(saved.id, now, "keep me")
        library.addHighlight(highlighted.id, "An excerpt for item 4", now)

        val report = library.applyRetention(now)
        assertEquals(1, report.excerptsDropped)
        assertEquals(1, report.articlesDeleted)

        assertEquals("", library.article(old.id)!!.excerpt)
        assertEquals(emptyList(), library.search("sentinel"))            // body no longer indexed
        assertEquals(listOf(old.id), library.search("number 1"))          // title still searchable
        assertEquals(null, library.article(ancient.id))
        assertNotNull(library.article(saved.id)).also { assertTrue(it.excerpt.isNotEmpty()) }
        assertNotNull(library.article(highlighted.id))
        assertNotNull(library.article(fresh.id))

        // A pruned item reappearing in a feed is not re-added as new.
        assertEquals(UpsertResult.Skipped, library.upsertArticle(null, item(2), now))
    }

    @Test fun historyRespectsTrackingAndDistinguishesEvents() {
        val a = assertIs<UpsertResult.Inserted>(library.upsertArticle(null, item(1), now))
        assertTrue(library.recordEvent(a.id, ReadingEvent.SummaryOpen, now))
        assertEquals(emptySet(), library.readIds()) // a summary open is not "read"
        library.tracking = false
        assertFalse(library.recordEvent(a.id, ReadingEvent.MarkRead, now))
        library.tracking = true
        library.recordEvent(a.id, ReadingEvent.MarkRead, now)
        assertEquals(setOf(a.id), library.readIds())
        library.clearHistory()
        assertEquals(0L, library.stats().history)
    }

    @Test fun layersFormTheProfile() {
        library.setLayer("LLMs", Layer.Core)
        library.setLayer("Crypto", Layer.Muted)
        library.budgetMinutes = 20
        val profile = library.profile()
        assertEquals(Layer.Core, profile.topics["LLMs"])
        assertEquals(Layer.Muted, profile.topics["Crypto"])
        assertEquals(20, profile.budgetMinutes)
    }

    @Test fun savedItemsExportAsMarkdown() {
        val a = assertIs<UpsertResult.Inserted>(library.upsertArticle(null, item(7, title = "Retrieval \"augmented\" generation"), now))
        library.save(a.id, now, "My note.")
        library.addHighlight(a.id, "An excerpt for item 7", now, "Key line", "abc123def4567890", "3")
        val files = MarkdownExport.export(library.savedItems())
        val (path, text) = files.entries.single()
        assertTrue(path.startsWith("Library/") && path.endsWith("/retrieval-augmented-generation.md"), path)
        assertTrue("title: \"Retrieval \\\"augmented\\\" generation\"" in text, text)
        assertTrue("> An excerpt for item 7" in text)
        assertTrue("passage 3 · version abc123def456" in text)
        assertTrue("## Notes\n\nMy note." in text)
    }
}
