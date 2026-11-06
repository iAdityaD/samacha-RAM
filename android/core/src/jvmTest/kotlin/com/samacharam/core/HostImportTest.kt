package com.samacharam.core

import com.samacharam.core.db.Library
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HostImportTest {
    private val dir: File = Files.createTempDirectory("samacha-import").toFile()
    private val driver = JdbcDriver(File(dir, "library.db"))
    private val library = Library(driver).also { it.open() }

    @AfterTest fun cleanup() { driver.close(); dir.deleteRecursively() }

    /** Shape of GET /api/export from the Python host (server/app.py state()). */
    private val export = """
    {
      "profile": {"topics": {"LLMs": "Core", "NLP": "Near", "Hardware": "Adjacent", "Bogus": "Sideways"}, "budget": 20, "tracking": false},
      "sources": [
        {"id": "google", "name": "Google Research", "url": "https://research.google/blog/rss/", "kind": "Blog", "topic": "LLMs", "enabled": true},
        {"id": "broken", "name": "Broken", "url": "ftp://nope", "kind": "Blog"}
      ],
      "articles": [
        {"id": "2005.11401", "title": "Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks", "url": "https://arxiv.org/abs/2005.11401",
         "source_id": "starter", "type": "Paper", "topics": ["LLMs", "NLP"], "excerpt": "The authors combine a parametric generation model with retrieved non-parametric information.",
         "authors": "Lewis et al.", "published": "2020-05-22T00:00:00+00:00", "fetched": null, "starter": true, "minutes": 2},
        {"id": "abc", "title": "Google post", "url": "https://research.google/blog/post/", "source_id": "google", "type": "Blog",
         "topics": ["LLMs"], "excerpt": "", "authors": "Google", "published": null, "fetched": "2026-10-18T10:00:00.123456+00:00"},
        {"id": "bad", "title": "", "url": "https://x.com/"}
      ],
      "saved": [{"id": "2005.11401", "created": "2026-10-18T11:00:00+00:00", "note": "Read section 3."}, {"id": "missing", "note": ""}],
      "history": [{"id": 1, "article": "2005.11401", "kind": "summary_open", "created": "2026-10-18T11:05:00+00:00", "version": "starter-1"}],
      "annotations": [{"id": "n1", "reference": {"article_id": "2005.11401", "quote": "parametric generation model", "document_version": "starter-1", "anchor_id": "excerpt"}, "note": "Core idea", "created": "2026-10-18T11:10:00+00:00"}],
      "models": "ignored"
    }
    """

    @Test fun importsHostExportFaithfully() {
        val report = HostImport.import(export, library, now = 1_800_000_000_000L)
        assertEquals(ImportReport(topics = 3, sources = 1, articles = 2, saved = 1, history = 1, highlights = 1, skipped = 4), report)

        val profile = library.profile()
        assertEquals(Layer.Core, profile.topics["LLMs"])
        assertEquals(20, profile.budgetMinutes)
        assertEquals(false, profile.tracking) // restored after importing history

        val saved = library.savedItems().single()
        assertEquals("Read section 3.", saved.note)
        assertEquals("parametric generation model", saved.highlights.single().quote)
        assertEquals(true, saved.article.starter)
        assertEquals(parseIsoInstant("2020-05-22"), saved.article.published)

        val post = library.articles().single { it.title == "Google post" }
        assertEquals("google", post.sourceKey)
        assertEquals(parseIsoInstant("2026-10-18T10:00:00.123+00:00"), post.firstSeen)
        assertEquals(1L, library.stats().history)
    }
}
