package com.samacharam.core.app

import com.samacharam.core.Article
import com.samacharam.core.ContentType
import com.samacharam.core.FeedMode
import com.samacharam.core.Layer
import com.samacharam.core.RankOptions
import com.samacharam.core.ReadingQueue
import com.samacharam.core.ai.AiException
import com.samacharam.core.ai.AiSettings
import com.samacharam.core.ai.AiTask
import com.samacharam.core.ai.AssistedRanking
import com.samacharam.core.ai.ModelResult
import com.samacharam.core.ai.ModelRouter
import com.samacharam.core.ai.ProviderConfig
import com.samacharam.core.ai.SecretStore
import com.samacharam.core.ai.TaskInput
import com.samacharam.core.ai.withProvider
import com.samacharam.core.ai.withoutProvider
import com.samacharam.core.briefing
import com.samacharam.core.db.Library
import com.samacharam.core.formatDate
import com.samacharam.core.layerOf
import com.samacharam.core.rank

data class FeedQuery(
    val mode: FeedMode = FeedMode.ForYou,
    val query: String = "",
    val type: ContentType? = null,
    val layer: Layer? = null,
    val missionId: String? = null,
)

/** One explained feed row: the item and why it is shown. */
data class FeedRow(val article: Article, val layer: Layer, val reason: String)

/** Screen-facing operations over the library. Rules decide; models only assist where the owner enabled them. */
object Feeds {
    fun today(library: Library, q: FeedQuery, now: Long): List<FeedRow> {
        val profile = library.profile()
        val matching = if (q.query.isBlank()) null else library.search(q.query, 300).toSet()
        val articles = library.articles().filter { matching == null || it.id in matching }
        val mission = q.missionId?.let { id -> activeMissions(library, now).firstOrNull { it.id == id } }
        val ranked = rank(
            articles, profile,
            RankOptions(
                type = q.type, layer = q.layer, mode = q.mode, mission = mission,
                following = library.sources().filter { it.enabled }.map { it.key }.toSet(),
                feedback = library.feedback(), now = now,
            ),
        )
        return ranked.map { FeedRow(it, layerOf(it.topics, profile), reason(it, profile.topics, q.mode, mission?.title)) }
    }

    fun activeMissions(library: Library, now: Long) = library.missions().filter { it.expires >= formatDate(now) }

    /** Daily queue; with assisted ranking enabled, the reranking model may reorder items within their layers. */
    fun dailyBriefing(library: Library, now: Long, router: ModelRouter? = null): Pair<ReadingQueue, String?> {
        val profile = library.profile()
        val queue = briefing(library.articles(), profile, library.readIds(), now, library.feedback())
        if (router == null || !library.aiSettings().policy.assistedRanking) return queue to null
        val (scores, error) = AssistedRanking.score(router, queue.articles, profile)
        return queue.copy(articles = AssistedRanking.apply(queue.articles, profile, scores)) to error
    }

    private fun reason(a: Article, layers: Map<String, Layer>, mode: FeedMode, mission: String?): String {
        if (mode == FeedMode.Following) return "From a source you follow, newest first."
        val matched = a.topics.filter { layers[it] != null && layers[it] != Layer.Muted }
        val layer = matched.maxByOrNull { layers.getValue(it).weight }
        return when {
            mission != null -> "Part of your mission “$mission”."
            layer != null -> "${layers.getValue(layer).name} interest: $layer."
            else -> "Discovery: outside your listed topics."
        }
    }
}

object ModelSetup {
    const val SAMPLE =
        "The team reports a 12 percent latency reduction for speech recognition on a single GPU. The result was measured on one benchmark only."

    /** Validates and saves a model. A new key replaces the old one; keys never go into settings. */
    fun save(library: Library, secrets: SecretStore, config: ProviderConfig, newKey: String?, clearKey: Boolean = false): AiSettings {
        val updated = library.aiSettings().withProvider(config)
        library.saveAiSettings(updated)
        when {
            clearKey -> secrets.remove(config.id)
            !newKey.isNullOrBlank() -> secrets.put(config.id, newKey)
        }
        return updated
    }

    fun remove(library: Library, secrets: SecretStore, id: String): AiSettings {
        val updated = library.aiSettings().withoutProvider(id)
        library.saveAiSettings(updated)
        secrets.remove(id)
        return updated
    }

    /** Sends one real request with fixed sample text, using the model's first task. */
    fun test(router: ModelRouter, config: ProviderConfig): ModelResult {
        val task = config.capabilities.minByOrNull { it.ordinal } ?: throw AiException("This model has no tasks.")
        val input = when (task) {
            AiTask.Classification -> TaskInput(SAMPLE, labels = listOf("AI audio", "Hardware", "Software"))
            AiTask.QA, AiTask.Evidence, AiTask.Reranking -> TaskInput(SAMPLE, "What did the team measure?")
            else -> TaskInput(SAMPLE)
        }
        return router.run(task, input, providerId = config.id)
    }
}

/** When to show the daily briefing reminder. Pure, so time zones are handled by the caller. */
object BriefingSchedule {
    fun minutes(time: String): Int = time.substringBefore(':').toInt() * 60 + time.substringAfter(':').toInt()

    /** Due once per local day, at or after the chosen local time. A missed time is caught up later that day. */
    fun due(enabled: Boolean, time: String, localDate: String, localMinutes: Int, lastDay: String?): Boolean =
        enabled && lastDay != localDate && localMinutes >= minutes(time)

    /** Refresh shortly before the briefing so it is ready on time. */
    fun shouldPrefetch(enabled: Boolean, time: String, localMinutes: Int, lastDay: String?, localDate: String): Boolean =
        enabled && lastDay != localDate && localMinutes in (minutes(time) - 45)..(minutes(time))
}
