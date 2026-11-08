package com.samacharam.core

import com.samacharam.core.ai.AiException
import com.samacharam.core.ai.AiSettings
import com.samacharam.core.ai.AiTask
import com.samacharam.core.ai.HttpResponse
import com.samacharam.core.ai.ModelRouter
import com.samacharam.core.ai.Protocol
import com.samacharam.core.ai.ProviderConfig
import com.samacharam.core.ai.Route
import com.samacharam.core.ai.SecretStore
import com.samacharam.core.ai.Transport
import com.samacharam.core.app.BriefingSchedule
import com.samacharam.core.app.FeedQuery
import com.samacharam.core.app.Feeds
import com.samacharam.core.app.ModelSetup
import com.samacharam.core.db.Library
import com.samacharam.core.db.UpsertResult
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppServicesTest {
    private val dir: File = Files.createTempDirectory("samacha-app").toFile()
    private val driver = JdbcDriver(File(dir, "library.db"))
    private val library = Library(driver).also { it.open(); it.seedDefaults() }
    private val now = 1_800_000_000_000L

    @AfterTest fun cleanup() { driver.close(); dir.deleteRecursively() }

    private fun add(n: Int, title: String, topic: String, source: String = "hf"): Long =
        (library.upsertArticle(source, FeedItem("https://e.com/$n", title, "", now - n * 60_000L, "Body $n.", ContentType.Blog, listOf(topic)), now) as UpsertResult.Inserted).id

    @Test fun todayRanksByLayerExplainsAndHonorsSearchAndMute() {
        val llm = add(1, "Retrieval study", "LLMs")
        val hw = add(2, "Chip roadmap", "Hardware")
        library.setLayer("Crypto", Layer.Muted)
        add(3, "Token prices", "Crypto")
        val rows = Feeds.today(library, FeedQuery(), now)
        assertEquals(listOf(llm, hw), rows.map { it.article.id })
        assertEquals("Core interest: LLMs.", rows.first().reason)
        assertEquals(listOf(hw), Feeds.today(library, FeedQuery(query = "chip"), now).map { it.article.id })
    }

    @Test fun missionsFilterWithoutChangingLayers() {
        add(1, "Retrieval study", "LLMs")
        val hw = add(2, "Chip roadmap", "Hardware")
        val id = library.addMission("Hardware week", setOf("Hardware"), "2099-01-01", now)
        val rows = Feeds.today(library, FeedQuery(missionId = id.toString()), now)
        assertEquals(listOf(hw), rows.map { it.article.id })
        assertTrue(rows.single().reason.contains("Hardware week"))
        assertEquals(Layer.Adjacent, library.profile().topics["Hardware"])
        library.addMission("Old", setOf("Hardware"), "2000-01-01", now)
        assertEquals(1, Feeds.activeMissions(library, now).size)
        assertFailsWith<IllegalArgumentException> { library.addMission("X", setOf("Unknown topic"), "2099-01-01", now) }
    }

    @Test fun feedbackIsExplicitRespectsTrackingAndClearsWithHistory() {
        val a = add(1, "Retrieval study", "LLMs")
        assertTrue(library.addFeedback(a, FeedbackReason.Useful, now))
        assertEquals(listOf("LLMs"), library.feedback().single().topics)
        library.tracking = false
        assertFalse(library.addFeedback(a, FeedbackReason.LessRelevant, now))
        library.tracking = true
        library.clearHistory()
        assertTrue(library.feedback().isEmpty())
    }

    @Test fun sourcesCanBeAddedValidatedAndRemovedWithoutLosingArticles() {
        val key = library.addSource("https://Blog.Example.com/feed.xml", "Example", ContentType.Blog, "Software")
        assertTrue(library.sources().any { it.key == key && it.url == "https://blog.example.com/feed.xml" })
        assertFailsWith<IllegalArgumentException> { library.addSource("https://blog.example.com/feed.xml", "Again", ContentType.Blog, "Software") }
        assertFailsWith<IllegalArgumentException> { library.addSource("http://insecure.example.com/rss", "Insecure", ContentType.Blog, "Software") }
        val a = add(1, "From example", "Software", source = key)
        library.removeSource(key)
        assertTrue(library.sources().none { it.key == key })
        assertEquals("", library.article(a)!!.sourceKey)
    }

    @Test fun topicsCanBeRemoved() {
        val a = add(1, "Chip roadmap", "Hardware")
        library.removeTopic("Hardware")
        assertFalse("Hardware" in library.profile().topics)
        assertTrue(library.article(a)!!.topics.isEmpty())
    }

    @Test fun sharedLinksAreSavedWithoutFetching() {
        val id = library.clip("https://news.example.com/story?utm_source=share", "A shared story", now)
        assertTrue(library.isSaved(id))
        assertEquals("https://news.example.com/story", library.article(id)!!.url)
        assertEquals(id, library.clip("https://news.example.com/story", null, now))
    }

    @Test fun notesRoundTrip() {
        val a = add(1, "Retrieval study", "LLMs")
        library.save(a, now, "Read section 3.")
        assertEquals("Read section 3.", library.note(a))
        assertEquals("", library.note(add(2, "Other", "LLMs")))
    }

    @Test fun historyListsEventsNewestFirst() {
        val a = add(1, "Retrieval study", "LLMs")
        val b = add(2, "Chip roadmap", "Hardware")
        library.recordEvent(a, ReadingEvent.SummaryOpen, now)
        library.recordEvent(b, ReadingEvent.MarkRead, now + 1)
        val history = library.historyEntries()
        assertEquals(listOf(b, a), history.map { it.article.id })
        assertEquals(ReadingEvent.MarkRead, history.first().event)
        assertEquals("Chip roadmap", history.first().article.title)
    }

    @Test fun relatedItemsShareTopicsAndSkipMuted() {
        val a = add(1, "Retrieval study", "LLMs")
        val b = add(2, "Another LLM paper", "LLMs")
        add(3, "Chip roadmap", "Hardware")
        library.upsertArticle("hf", FeedItem("https://e.com/m", "Muted mix", "", now, "x", ContentType.Blog, listOf("LLMs", "Crypto")), now)
        library.setLayer("Crypto", Layer.Muted)
        assertEquals(listOf(b), library.related(a).map { it.id })
    }

    @Test fun storageStatsIncludeDetails() {
        add(1, "Retrieval study", "LLMs")
        val stats = library.stats()
        assertEquals(1L, stats.articles)
        assertTrue(stats.excerptBytes > 0)
        assertEquals("3", library.meta("schema_version"))
    }

    @Test fun briefingScheduleOncePerDayAndCatchesUp() {
        assertFalse(BriefingSchedule.due(true, "08:00", "2026-11-07", 7 * 60 + 59, null))
        assertTrue(BriefingSchedule.due(true, "08:00", "2026-11-07", 8 * 60, null))
        assertTrue(BriefingSchedule.due(true, "08:00", "2026-11-07", 20 * 60, "2026-11-06"))
        assertFalse(BriefingSchedule.due(true, "08:00", "2026-11-07", 9 * 60, "2026-11-07"))
        assertFalse(BriefingSchedule.due(false, "08:00", "2026-11-07", 9 * 60, null))
        assertTrue(BriefingSchedule.shouldPrefetch(true, "08:00", 7 * 60 + 30, null, "2026-11-07"))
        assertFailsWith<IllegalArgumentException> { library.briefingTime = "25:00" }
    }

    private class MemorySecrets : SecretStore {
        val map = mutableMapOf<String, String>()
        override fun get(providerId: String) = map[providerId]
        override fun put(providerId: String, secret: String) { map[providerId] = secret }
        override fun remove(providerId: String) { map.remove(providerId) }
    }

    @Test fun modelSetupStoresKeysSeparatelyAndTestsTheModel() {
        val secrets = MemorySecrets()
        val config = ProviderConfig("p1", "My API", Protocol.OpenAiCompatible, "m", "https://api.example.com/v1", setOf(AiTask.Summarization), allowCloud = true)
        ModelSetup.save(library, secrets, config, "sk-abc")
        assertEquals("sk-abc", secrets.map["p1"])
        assertFalse("sk-abc" in library.meta("ai.settings")!!)
        library.saveAiSettings(library.aiSettings().copy(routes = mapOf(AiTask.Summarization to Route("p1"))))

        var sentKey: String? = null
        val transport = Transport { r ->
            sentKey = r.headers["authorization"]
            HttpResponse(200, """{"choices":[{"message":{"content":"{\"sentences\":[{\"text\":\"Latency fell.\",\"quote\":\"12 percent latency reduction\"}]}"}}]}""")
        }
        val router = ModelRouter(library::aiSettings, secrets, transport, library.cloudUsage, clock = { now })
        assertEquals("p1", ModelSetup.test(router, config).providerId)
        assertEquals("Bearer sk-abc", sentKey)

        ModelSetup.remove(library, secrets, "p1")
        assertTrue(secrets.map.isEmpty())
        assertEquals(Route(), library.aiSettings().route(AiTask.Summarization))
        assertFailsWith<AiException> { ModelSetup.save(library, secrets, config.copy(allowCloud = false), null) }
    }

    @Test fun assistedBriefingIsOptIn() {
        add(1, "Retrieval study", "LLMs")
        val router = ModelRouter({ AiSettings() }, MemorySecrets(), { error("no network") }, library.cloudUsage, clock = { now })
        val (queue, error) = Feeds.dailyBriefing(library, now, router)
        assertEquals(1, queue.articles.size)
        assertEquals(null, error)
    }
}
