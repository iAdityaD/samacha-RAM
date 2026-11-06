package com.samacharam.core.ai

import com.samacharam.core.Article
import com.samacharam.core.Layer
import com.samacharam.core.Profile
import com.samacharam.core.layerOf

/**
 * Optional model-assisted ordering. The rule-based ranking stays in charge:
 * a model may only reorder items inside the same interest layer, can never add or surface muted items,
 * and any failure leaves the rule-based order unchanged.
 */
object AssistedRanking {

    fun interestQuestion(profile: Profile): String {
        fun names(layer: Layer) = profile.topics.filterValues { it == layer }.keys.sorted().joinToString(", ")
        return listOfNotNull(
            names(Layer.Core).ifEmpty { null }?.let { "Core interests: $it." },
            names(Layer.Near).ifEmpty { null }?.let { "Closely related interests: $it." },
            names(Layer.Adjacent).ifEmpty { null }?.let { "Adjacent interests: $it." },
        ).joinToString(" ").ifEmpty { "General technology research and news." } +
            " How useful is this item for someone with these interests?"
    }

    /** Scores up to [limit] items with the reranking route. Stops at the first failure (budget, privacy, network) and keeps partial scores. */
    fun score(router: ModelRouter, ranked: List<Article>, profile: Profile, limit: Int = 10): Pair<Map<Long, Double>, String?> {
        val question = interestQuestion(profile)
        val scores = LinkedHashMap<Long, Double>()
        for (article in ranked.take(limit)) {
            val text = (article.title + "\n\n" + article.excerpt).take(6_000)
            val result = try {
                router.run(AiTask.Reranking, TaskInput(text, question))
            } catch (e: AiException) {
                return scores to e.message
            }
            (result.output as? TaskOutput.Score)?.let { scores[article.id] = it.score }
        }
        return scores to null
    }

    /** Reorders scored items among the positions scored items already hold within each layer. */
    fun apply(ranked: List<Article>, profile: Profile, scores: Map<Long, Double>): List<Article> {
        val visible = ranked.filter { layerOf(it.topics, profile) != Layer.Muted }
        val result = visible.toMutableList()
        for (layer in Layer.entries) {
            val positions = visible.indices.filter { layerOf(visible[it].topics, profile) == layer && visible[it].id in scores }
            val reordered = positions.map { visible[it] }.sortedByDescending { scores.getValue(it.id) }
            positions.zip(reordered).forEach { (position, article) -> result[position] = article }
        }
        return result
    }
}
