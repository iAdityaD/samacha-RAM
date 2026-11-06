package com.samacharam.core

import com.samacharam.core.db.Library
import com.samacharam.core.feeds.FeedResponse
import com.samacharam.core.feeds.FeedTransport
import com.samacharam.core.feeds.Refresher
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefresherTest {
    private val dir: File = Files.createTempDirectory("samacha-refresh").toFile()
    private val driver = JdbcDriver(File(dir, "library.db"))
    private val library = Library(driver).also { it.open(); it.seedDefaults() }
    private var now = 1_800_000_000_000L
    private val requests = mutableListOf<Pair<String, Map<String, String>>>()
    private var responder: (String, Map<String, String>) -> FeedResponse = { _, _ -> FeedResponse(404, emptyMap(), ByteArray(0)) }
    private val transport = FeedTransport { url, headers -> requests += url to headers; responder(url, headers) }

    @AfterTest fun cleanup() { driver.close(); dir.deleteRecursively() }

    private fun rss(vararg titles: String) = """<rss><channel><title>T</title>${titles.joinToString("") {
        "<item><title>$it</title><link>https://news.example.com/${it.lowercase().replace(' ', '-')}</link><description>About $it.</description><pubDate>Fri, 06 Nov 2026 00:00:00 GMT</pubDate></item>"
    }}</channel></rss>""".encodeToByteArray()

    private fun ok(body: ByteArray, etag: String? = null) = FeedResponse(200, listOfNotNull(etag?.let { "etag" to it }).toMap(), body)

    private fun onlySource(key: String = "hf") {
        library.sources().filter { it.key != key }.forEach { library.setSourceEnabled(it.key, false) }
    }

    private fun refresher() = Refresher(library, transport, { now })

    @Test fun firstRunHasDefaultSourcesAndLayersButNoSampleArticles() {
        assertEquals(5, library.sources().size)
        assertEquals(Layer.Core, library.profile().topics["LLMs"])
        assertTrue(library.articles().isEmpty())
        assertEquals("2", library.meta("schema_version"))
    }

    @Test fun fetchesParsesAndTagsWithOwnersTopics() {
        onlySource()
        responder = { _, _ -> ok(rss("New GPU accelerator benchmark", "Gardening tips"), etag = "\"v1\"") }
        val report = refresher().refresh()
        assertEquals(2, report.inserted)
        val articles = library.articles().associateBy { it.title }
        assertEquals(listOf("Hardware"), articles.getValue("New GPU accelerator benchmark").topics)
        assertEquals(listOf("LLMs"), articles.getValue("Gardening tips").topics) // no rule matched: the source's topic
        val hf = library.sources().single { it.key == "hf" }
        assertEquals("\"v1\"", hf.etag)
        assertNull(hf.lastError)
        assertEquals(2, hf.itemCount)
    }

    @Test fun conditionalRequestsAndNotModified() {
        onlySource()
        responder = { _, _ -> ok(rss("One"), etag = "\"v1\"") }
        refresher().refresh()
        responder = { _, h -> if (h["if-none-match"] == "\"v1\"") FeedResponse(304, emptyMap(), ByteArray(0)) else error("expected a conditional request") }
        now += 2 * 60 * 60_000L
        val report = refresher().refresh()
        assertEquals("unchanged", report.sources.single().status)
        assertEquals(1, library.articles().size)
    }

    @Test fun sourcesAreNotFetchedBeforeTheyAreDue() {
        onlySource()
        responder = { _, _ -> ok(rss("One")) }
        refresher().refresh()
        now += 10 * 60_000L
        assertTrue(refresher().refresh().sources.isEmpty())
        assertEquals(1, refresher().refresh(force = true).sources.size)
    }

    @Test fun failuresKeepArticlesAndBackOffExponentially() {
        onlySource()
        responder = { _, _ -> ok(rss("Keep me")) }
        refresher().refresh()
        responder = { _, _ -> FeedResponse(500, emptyMap(), ByteArray(0)) }
        now += 61 * 60_000L
        val report = refresher().refresh()
        assertEquals(1, report.failed)
        assertEquals(1, library.articles().size)
        val first = library.sources().single { it.key == "hf" }
        assertEquals(1, first.failures)
        assertEquals(now + 60 * 60_000L, first.nextCheck)
        now = first.nextCheck!!
        refresher().refresh()
        assertEquals(now + 120 * 60_000L, library.sources().single { it.key == "hf" }.nextCheck)
        assertEquals(24 * 60 * 60_000L, Refresher.backoffMs(20, 60))
    }

    @Test fun retryAfterIsRespected() {
        onlySource()
        responder = { _, _ -> FeedResponse(429, mapOf("retry-after" to "7200"), ByteArray(0)) }
        refresher().refresh()
        assertEquals(now + 7_200_000L, library.sources().single { it.key == "hf" }.nextCheck)
    }

    @Test fun permanentRedirectMovesTheSubscriptionTemporaryDoesNot() {
        onlySource()
        responder = { url, _ ->
            when (url) {
                "https://huggingface.co/blog/feed.xml" -> FeedResponse(301, mapOf("location" to "/blog/feed-v2.xml"), ByteArray(0))
                "https://huggingface.co/blog/feed-v2.xml" -> ok(rss("Moved"))
                else -> error(url)
            }
        }
        refresher().refresh()
        assertEquals("https://huggingface.co/blog/feed-v2.xml", library.sources().single { it.key == "hf" }.url)

        responder = { url, _ -> if (url.endsWith("feed-v2.xml")) FeedResponse(302, mapOf("location" to "https://mirror.example.com/f.xml"), ByteArray(0)) else ok(rss("Mirror")) }
        refresher().refresh(force = true)
        assertEquals("https://huggingface.co/blog/feed-v2.xml", library.sources().single { it.key == "hf" }.url)
    }

    @Test fun insecureAndBrokenFeedsFailSafely() {
        onlySource()
        responder = { _, _ -> FeedResponse(301, mapOf("location" to "http://huggingface.co/blog/feed.xml"), ByteArray(0)) }
        assertEquals("Feeds must use HTTPS.", refresher().refresh().sources.single().error)
        responder = { _, _ -> ok("<html>not a feed</html>".encodeToByteArray()) }
        assertTrue(refresher().refresh(force = true).sources.single().error!!.startsWith("Could not read the feed"))
        responder = { _, _ -> throw java.io.IOException("connect to https://huggingface.co/ timed out") }
        assertEquals("Could not reach the feed.", refresher().refresh(force = true).sources.single().error)
        responder = { _, _ -> ok(ByteArray(Refresher.MAX_BYTES + 1)) }
        assertEquals("Feed is larger than 3 MB.", refresher().refresh(force = true).sources.single().error)
    }

    @Test fun refreshAppliesRetention() {
        onlySource()
        responder = { _, _ -> ok(rss("Old news")) }
        refresher().refresh()
        now += 100 * DAY_MS
        responder = { _, _ -> FeedResponse(304, emptyMap(), ByteArray(0)) }
        refresher().refresh(force = true)
        assertTrue(library.articles().isEmpty())
    }
}
