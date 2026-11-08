package com.samacharam.core

/** Interest layers. Weights order the For You feed; Muted always excludes. */
enum class Layer(val weight: Int, val code: Int) {
    Core(4, 4), Near(3, 3), Adjacent(2, 2), Discovery(1, 1), Muted(-999, 0);

    companion object {
        fun fromCode(code: Int): Layer = entries.firstOrNull { it.code == code } ?: Discovery
        fun fromName(name: String): Layer? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

enum class ContentType(val code: Int) {
    Article(0), News(1), Blog(2), Paper(3);

    companion object {
        fun fromCode(code: Int): ContentType = entries.firstOrNull { it.code == code } ?: Article
        fun fromName(name: String): ContentType = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Article
    }
}

/** Reading events are distinct on purpose: opening a summary is not reading the full paper. */
enum class ReadingEvent(val code: Int, val wire: String) {
    MetadataOpen(1, "metadata_open"), SummaryOpen(2, "summary_open"), OriginalOpen(3, "original_open"),
    MarkRead(4, "mark_read"), DocumentOpen(5, "document_open");

    companion object {
        fun fromWire(value: String): ReadingEvent? = entries.firstOrNull { it.wire == value }
    }
}

enum class FeedbackReason(val wire: String) {
    Useful("useful"), LessRelevant("less_relevant"), Duplicate("duplicate"), PoorEvidence("poor_evidence"), NotNow("not_now");
}

data class Profile(
    val topics: Map<String, Layer>,
    val budgetMinutes: Int = 15,
    val tracking: Boolean = true,
)

/** An item as it is ranked and displayed. Times are epoch milliseconds (UTC). */
data class Article(
    val id: Long,
    val url: String,
    val sourceKey: String,
    val title: String,
    val authors: String,
    val published: Long?,
    val firstSeen: Long,
    val type: ContentType,
    val topics: List<String>,
    val excerpt: String,
    val minutes: Int,
    val starter: Boolean = false,
    /** The source's display name, e.g. "Hugging Face"; empty for shared links. */
    val sourceName: String = "",
)

data class Feedback(val articleId: Long, val topics: List<String>, val reason: FeedbackReason, val createdAt: Long)

/** A temporary goal. It filters a feed but never changes global layers. `expires` is yyyy-MM-dd. */
data class Mission(val id: String, val title: String, val topics: Set<String>, val expires: String)

/** A parsed feed entry before it is stored. */
data class FeedItem(
    val url: String,
    val title: String,
    val authors: String,
    val published: Long?,
    val excerpt: String,
    val type: ContentType,
    val topics: List<String>,
)
