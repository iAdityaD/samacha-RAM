package com.samacharam.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlTest {
    @Test fun keepsTrailingSlashAndPath() =
        assertEquals("https://research.google/blog/rss/", canonicalUrl("https://Research.Google/blog/rss/"))

    @Test fun removesTrackingParametersAndFragment() =
        assertEquals("https://a.com/p?id=7", canonicalUrl("https://a.com/p?utm_source=x&id=7&fbclid=y#top"))

    @Test fun removesDefaultPortAndAddsRootPath() {
        assertEquals("https://a.com/", canonicalUrl("https://a.com:443"))
        assertEquals("http://a.com:8080/", canonicalUrl("http://a.com:8080"))
    }

    @Test fun rejectsCredentialsAndOtherSchemes() {
        assertFailsWith<IllegalArgumentException> { canonicalUrl("https://user:pw@a.com/") }
        assertFailsWith<IllegalArgumentException> { canonicalUrl("ftp://a.com/") }
        assertFailsWith<IllegalArgumentException> { canonicalUrl("not a url") }
    }

    @Test fun hashIsStable() = assertEquals(fnv64("https://a.com/"), fnv64("https://a.com/"))
}

class DedupTest {
    private val day = DAY_MS

    @Test fun syndicatedCopyIsDuplicate() = assertTrue(
        isNearDuplicate("Acme releases Falcon-9B open speech recognition model", 0, "Acme Releases Falcon-9B Open Speech Recognition Model!", day)
    )

    @Test fun differentEventsAreNotDuplicates() = assertFalse(
        isNearDuplicate("Acme releases Falcon-9B speech recognition model", 0, "Acme speech recognition model benchmarked on phones", day)
    )

    @Test fun sameTitleFarApartIsNotDuplicate() = assertFalse(
        isNearDuplicate("Weekly roundup of language model research news", 0, "Weekly roundup of language model research news", 30 * day)
    )

    @Test fun unknownDatesAreNeverMerged() = assertFalse(
        isNearDuplicate("Acme releases Falcon-9B open speech model", null, "Acme releases Falcon-9B open speech model", null)
    )
}

class RankingTest {
    private val profile = Profile(mapOf("LLMs" to Layer.Core, "NLP" to Layer.Near, "Hardware" to Layer.Adjacent, "Crypto" to Layer.Muted), 10)
    private fun a(id: Long, topic: String, source: String = "s$id", published: Long = id, minutes: Int = 2) =
        Article(id, "https://x.com/$id", source, "Title $id", "", published, 0, ContentType.Blog, listOf(topic), "excerpt", minutes)

    @Test fun mutedNeverAppearsAndCoreLeads() {
        val ranked = rank(listOf(a(1, "Hardware"), a(2, "Crypto"), a(3, "LLMs"), a(4, "NLP")), profile)
        assertEquals(listOf(3L, 4L, 1L), ranked.map { it.id })
    }

    @Test fun anyMutedTopicMutesTheItem() =
        assertEquals(Layer.Muted, layerOf(listOf("LLMs", "Crypto"), profile))

    @Test fun followingIsChronologicalAndSourceScoped() {
        val ranked = rank(listOf(a(1, "Hardware", "x"), a(2, "LLMs", "y"), a(3, "NLP", "x")), profile, RankOptions(mode = FeedMode.Following, following = setOf("x")))
        assertEquals(listOf(3L, 1L), ranked.map { it.id })
    }

    @Test fun missionFiltersWithoutFeedback() {
        val mission = Mission("m", "Hardware week", setOf("Hardware"), "2099-01-01")
        val ranked = rank(listOf(a(1, "Hardware"), a(2, "LLMs")), profile, RankOptions(mission = mission))
        assertEquals(listOf(1L), ranked.map { it.id })
    }

    @Test fun feedbackReordersOnlyWithinALayer() {
        val items = listOf(a(1, "NLP"), a(2, "LLMs"), a(3, "NLP"))
        val feedback = listOf(Feedback(99, listOf("NLP"), FeedbackReason.Useful, 0))
        val ranked = rank(items, profile, RankOptions(feedback = feedback, now = 0))
        assertEquals(2L, ranked.first().id) // Core still first despite NLP feedback
    }

    @Test fun diversityBreaksLongSingleSourceRuns() {
        val items = listOf(a(1, "LLMs", "x", 10), a(2, "LLMs", "x", 9), a(3, "LLMs", "x", 8), a(4, "LLMs", "y", 7))
        val sources = rank(items, profile).map { it.sourceKey }
        assertEquals(listOf("x", "x", "y", "x"), sources)
    }

    @Test fun queueMinutesMatchReturnedItems() {
        val items = (1L..30L).map { a(it, "LLMs", minutes = 1) }
        val queue = readingQueue(items, budgetMinutes = 60, maxItems = 15)
        assertEquals(15, queue.articles.size)
        assertEquals(15, queue.minutes)
    }

    @Test fun briefingSkipsReadItemsAndRespectsBudget() {
        val items = listOf(a(1, "LLMs", minutes = 6), a(2, "LLMs", minutes = 6), a(3, "NLP", minutes = 3))
        val queue = briefing(items, profile, readIds = setOf(2L), now = 0)
        assertEquals(listOf(1L, 3L), queue.articles.map { it.id })
        assertTrue(queue.minutes <= profile.budgetMinutes)
    }
}

class TimeTest {
    @Test fun parsesHostTimestamps() {
        assertEquals(0L, parseIsoInstant("1970-01-01T00:00:00+00:00"))
        assertEquals(1_590_105_600_000L, parseIsoInstant("2020-05-22T00:00:00+00:00"))
        assertEquals(1_590_105_600_123L, parseIsoInstant("2020-05-22T05:30:00.123456+05:30"))
        assertEquals(1_590_105_600_000L, parseIsoInstant("2020-05-22"))
        assertEquals(null, parseIsoInstant("yesterday"))
    }

    @Test fun formatsDates() {
        assertEquals("2020-05-22", formatDate(1_590_105_600_000L))
        assertEquals("1969-12-31", formatDate(-1L))
        assertEquals("2024-02-29", formatDate(parseIsoInstant("2024-02-29T12:00:00Z")!!))
    }
}

class VectorTest {
    @Test fun quantizedCosineIsCloseToFloatCosine() {
        val a = FloatArray(384) { kotlin.math.sin(it * 0.37f) }
        val b = FloatArray(384) { kotlin.math.sin(it * 0.37f + 0.4f) }
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val exact = dot / kotlin.math.sqrt(na * nb)
        val approx = cosine(quantize(a), quantize(b))
        assertTrue(kotlin.math.abs(exact - approx) < 0.01, "exact=$exact approx=$approx")
    }

    @Test fun storesInFourBytesPlusOnePerDimension() {
        val q = quantize(FloatArray(384) { it / 384f })
        val bytes = q.toBytes()
        assertEquals(388, bytes.size)
        assertEquals(q.values.toList(), QuantizedVector.fromBytes(bytes).values.toList())
        assertEquals(q.scale, QuantizedVector.fromBytes(bytes).scale)
    }

    @Test fun rejectsMismatchedSpaces() {
        assertFailsWith<IllegalArgumentException> { cosine(quantize(FloatArray(3) { 1f }), quantize(FloatArray(4) { 1f })) }
    }
}

class CodecTest {
    @Test fun shortTextStaysPlain() {
        val stored = TextCodec.encode("short note")
        assertEquals(0, stored[0].toInt())
        assertEquals("short note", TextCodec.decode(stored))
    }

    @Test fun longTextIsCompressedAndRoundTrips() {
        val text = "Retrieval-augmented generation combines retrieval with generation — दस्तावेज़ ✓. ".repeat(40)
        val stored = TextCodec.encode(text)
        assertEquals(1, stored[0].toInt())
        assertTrue(stored.size < text.encodeToByteArray().size / 4)
        assertEquals(text, TextCodec.decode(stored))
    }

    @Test fun corruptDataFailsSafely() {
        assertFailsWith<IllegalArgumentException> { TextCodec.decode(byteArrayOf(1, 9, 9, 9)) }
        assertFailsWith<IllegalArgumentException> { TextCodec.decode(byteArrayOf(7)) }
    }
}
