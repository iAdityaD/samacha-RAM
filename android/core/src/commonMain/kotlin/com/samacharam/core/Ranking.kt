package com.samacharam.core

import kotlin.math.exp
import kotlin.math.max

enum class FeedMode { ForYou, Following }

data class RankOptions(
    val query: String = "",
    val type: ContentType? = null,
    val layer: Layer? = null,
    val mode: FeedMode = FeedMode.ForYou,
    val mission: Mission? = null,
    val following: Set<String> = emptySet(),
    val feedback: List<Feedback> = emptyList(),
    val now: Long = 0L,
)

/** The strongest layer among an item's topics. Unknown topics count as Discovery; any Muted topic mutes. */
fun layerOf(topics: List<String>, profile: Profile): Layer {
    val layers = topics.map { profile.topics[it] ?: Layer.Discovery }
    if (Layer.Muted in layers) return Layer.Muted
    return layers.maxByOrNull { it.weight } ?: Layer.Discovery
}

private const val DAY = 86_400_000.0

/**
 * Explicit layers dominate; feedback only reorders items inside the same layer, with
 * short (7-day) and long (60-day) decay. Missions ignore inferred signals.
 */
private fun inferredScore(article: Article, options: RankOptions): Double {
    if (options.mission != null) return 0.0
    var score = 0.0
    for (f in options.feedback) {
        val age = max(0.0, (options.now - f.createdAt) / DAY)
        val sign = when (f.reason) { FeedbackReason.Useful -> 1.0; FeedbackReason.LessRelevant -> -1.0; else -> 0.0 }
        if (f.topics.any { it in article.topics }) score += sign * (0.65 * exp(-age / 7) + 0.35 * exp(-age / 60))
        if (f.articleId == article.id && f.reason == FeedbackReason.Duplicate) score -= 2.0
    }
    return score
}

fun rank(articles: List<Article>, profile: Profile, options: RankOptions = RankOptions()): List<Article> {
    val words = options.query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val filtered = articles.filter { a ->
        val layer = layerOf(a.topics, profile)
        if (layer == Layer.Muted) return@filter false
        if (options.type != null && a.type != options.type) return@filter false
        if (options.layer != null && layer != options.layer) return@filter false
        if (options.mode == FeedMode.Following && a.sourceKey !in options.following) return@filter false
        if (options.mission != null && a.topics.none { it in options.mission.topics }) return@filter false
        val haystack = (listOf(a.title, a.authors, a.excerpt) + a.topics).joinToString(" ").lowercase()
        words.all { it in haystack }
    }
    // One record per URL, preferring fetched records over historical starter records.
    val unique = filtered.sortedBy { it.starter }.distinctBy { it.url }

    val chronological = compareByDescending<Article> { it.published ?: Long.MIN_VALUE }.thenBy { it.id }
    if (options.mode == FeedMode.Following) return unique.sortedWith(chronological)

    val inferred = unique.associate { it.id to inferredScore(it, options) }
    val sorted = unique.sortedWith(
        compareByDescending<Article> { layerOf(it.topics, profile).weight }
            .thenByDescending { inferred.getValue(it.id) }
            .then(chronological)
    ).toMutableList()

    // Source diversity: avoid three consecutive items from one source when another source in the same layer is available.
    val diverse = ArrayList<Article>(sorted.size)
    while (sorted.isNotEmpty()) {
        var index = 0
        if (diverse.size >= 2 && diverse[diverse.size - 1].sourceKey == diverse[diverse.size - 2].sourceKey &&
            sorted[0].sourceKey == diverse.last().sourceKey
        ) {
            val layer = layerOf(sorted[0].topics, profile)
            val alternative = sorted.indexOfFirst { it.sourceKey != diverse.last().sourceKey && layerOf(it.topics, profile) == layer }
            if (alternative >= 0) index = alternative
        }
        diverse += sorted.removeAt(index)
    }
    return diverse
}

data class ReadingQueue(val articles: List<Article>, val minutes: Int, val budgetMinutes: Int)

/** A finite queue: stops at the time budget or the item cap, and reports minutes for exactly the returned items. */
fun readingQueue(ranked: List<Article>, budgetMinutes: Int, maxItems: Int = 15): ReadingQueue {
    val selected = ArrayList<Article>()
    var used = 0
    for (a in ranked) {
        if (selected.size >= maxItems) break
        val minutes = max(1, a.minutes)
        if (used + minutes > budgetMinutes) continue
        selected += a
        used += minutes
    }
    return ReadingQueue(selected, used, budgetMinutes)
}

/** Daily briefing: For You ranking, without items the owner already marked read, within the time budget. */
fun briefing(articles: List<Article>, profile: Profile, readIds: Set<Long>, now: Long, feedback: List<Feedback> = emptyList()): ReadingQueue {
    val ranked = rank(articles.filter { it.id !in readIds }, profile, RankOptions(feedback = feedback, now = now))
    return readingQueue(ranked, profile.budgetMinutes)
}

fun estimatedMinutes(text: String): Int = max(1, (text.split(Regex("\\s+")).count { it.isNotEmpty() } + 100) / 200)
