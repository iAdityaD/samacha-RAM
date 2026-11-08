package com.samacharam.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samacharam.app.ui.Kicker
import com.samacharam.app.ui.Radius
import com.samacharam.app.ui.SheetAction
import com.samacharam.app.ui.Space
import com.samacharam.app.ui.TypeRole
import com.samacharam.app.ui.colors
import com.samacharam.app.ui.hostOf
import com.samacharam.core.Article
import com.samacharam.core.FeedbackReason
import com.samacharam.core.Layer
import com.samacharam.core.ReadingEvent
import com.samacharam.core.app.FeedQuery
import com.samacharam.core.app.Feeds
import com.samacharam.core.db.Library
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Long-press actions for any story, defined once so every list behaves the same. */
fun storyActions(ctx: AppContext, a: Article): List<SheetAction> = listOf(
    SheetAction("Read at ${hostOf(a.url)}", Icons.AutoMirrored.Outlined.OpenInNew) { ctx.openOriginal(a) },
    SheetAction("Mark as read", Icons.Outlined.DoneAll) { ctx.markRead(a.id) },
    SheetAction("More like this", Icons.Outlined.ThumbUp, "Moves similar items up within their layer") { ctx.feedback(a.id, FeedbackReason.Useful) },
    SheetAction("Less like this", Icons.Outlined.ThumbDown, "Moves similar items down within their layer") { ctx.feedback(a.id, FeedbackReason.LessRelevant) },
    SheetAction("Already seen elsewhere", Icons.Outlined.VisibilityOff) { ctx.feedback(a.id, FeedbackReason.Duplicate) },
    SheetAction("Share link", Icons.Outlined.Share) { ctx.share(a) },
)

/** Refreshes sources. Speaks only when something changed. */
fun refreshSources(ctx: AppContext) = ctx.run {
    val r = PhoneLibrary.refresher(ctx.activity).refresh(force = true)
    ctx.library.setMeta("ui.lastRefresh", System.currentTimeMillis().toString())
    when {
        r.inserted > 0 -> "${r.inserted} new item${if (r.inserted == 1) "" else "s"}" + if (r.failed > 0) " · ${r.failed} source${if (r.failed == 1) "" else "s"} unavailable" else ""
        r.failed > 0 -> "${r.failed} source${if (r.failed == 1) "" else "s"} could not be reached. Earlier items are kept."
        else -> "Up to date"
    }
}

/** A deck of stories played full screen: the daily briefing, a topic, or a mission. */
object Decks {
    const val BRIEFING = "briefing"
    fun topic(name: String) = "topic:$name"
    fun mission(id: String) = "mission:$id"

    fun title(deck: String) = when {
        deck == BRIEFING -> "Today's briefing"
        deck.startsWith("topic:") -> deck.removePrefix("topic:")
        else -> "Mission"
    }

    fun load(library: Library, deck: String, now: Long): Pair<String, List<Article>> = when {
        deck == BRIEFING -> "Today's briefing" to Feeds.dailyBriefing(library, now).first.articles
        deck.startsWith("topic:") -> {
            val name = deck.removePrefix("topic:")
            name to topicStories(library, name)
        }
        else -> {
            val id = deck.removePrefix("mission:")
            val m = library.missions().firstOrNull { it.id == id }
            (m?.title ?: "Mission") to Feeds.today(library, FeedQuery(missionId = id), now).map { it.article }.take(12)
        }
    }

    /** Unread items first, newest first; falls back to the newest when everything is read. */
    fun topicStories(library: Library, name: String, limit: Int = 12): List<Article> {
        val seen = library.seenIds()
        val all = library.articles().filter { name in it.topics }.sortedByDescending { it.published ?: it.firstSeen }
        return all.filter { it.id !in seen }.take(limit).ifEmpty { all.take(limit) }
    }
}

/** Topics worth a story circle: followed ones, Core first, with how many unread items each holds. */
fun topicCircles(library: Library): List<Triple<String, Layer, Int>> {
    val seen = library.seenIds()
    val articles = library.articles()
    return library.profile().topics.filterValues { it == Layer.Core || it == Layer.Near }
        .map { (t, l) -> Triple(t, l, articles.count { t in it.topics && it.id !in seen }) }
        .filter { (t, _, _) -> articles.any { t in it.topics } }
        .sortedWith(compareByDescending<Triple<String, Layer, Int>> { it.second.weight }.thenByDescending { it.third })
}

/** Consecutive days, ending today or yesterday, with any reading activity. */
fun readingStreak(library: Library): Int {
    val zone = ZoneId.systemDefault()
    val days = library.historyEntries(2_000).map { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.toSet()
    var day = LocalDate.now().let { if (it in days) it else it.minusDays(1) }
    var n = 0
    while (day in days) { n++; day = day.minusDays(1) }
    return n
}

@Composable
fun SearchField(value: String, placeholder: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.control)).background(colors.paperSunk).heightIn(min = 44.dp).padding(start = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = colors.inkMuted)
        Spacer(Modifier.width(Space.s))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = TypeRole.ui, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(value, onChange, singleLine = true, textStyle = TypeRole.ui.copy(color = colors.ink), cursorBrush = SolidColor(colors.evergreen),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder })
        }
        if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Clear, contentDescription = "Clear", tint = colors.inkMuted) }
        else Spacer(Modifier.width(Space.m))
    }
}

/** A passage set off with a left rule: notes and highlights. */
@Composable
fun QuoteBlock(text: String, caption: String?, modifier: Modifier = Modifier, serif: Boolean = false) {
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (serif) colors.saffron else colors.evergreen))
        Spacer(Modifier.width(Space.m + 1.dp))
        Column {
            Text(text, style = if (serif) TypeRole.body.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) else TypeRole.ui,
                color = colors.ink, maxLines = 6, overflow = TextOverflow.Ellipsis)
            caption?.let { Spacer(Modifier.height(Space.xs)); Kicker(it) }
        }
    }
}

fun eventLabel(e: ReadingEvent) = when (e) {
    ReadingEvent.MetadataOpen -> "Opened"
    ReadingEvent.SummaryOpen -> "Summary viewed"
    ReadingEvent.OriginalOpen -> "Read at publisher"
    ReadingEvent.MarkRead -> "Marked read"
    ReadingEvent.DocumentOpen -> "Document opened"
}
