package com.samacharam.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.samacharam.app.ui.ActionSheet
import com.samacharam.app.ui.Banner
import com.samacharam.app.ui.EmptyState
import com.samacharam.app.ui.Hairline
import com.samacharam.app.ui.Kicker
import com.samacharam.app.ui.LayerMark
import com.samacharam.app.ui.MenuRow
import com.samacharam.app.ui.Pill
import com.samacharam.app.ui.PrimaryButton
import com.samacharam.app.ui.Radius
import com.samacharam.app.ui.SecondaryButton
import com.samacharam.app.ui.SectionLabel
import com.samacharam.app.ui.SheetAction
import com.samacharam.app.ui.Space
import com.samacharam.app.ui.StoryRow
import com.samacharam.app.ui.StorySkeleton
import com.samacharam.app.ui.TextTabs
import com.samacharam.app.ui.TypeRole
import com.samacharam.app.ui.colors
import com.samacharam.app.ui.hostOf
import com.samacharam.app.ui.timeAgo
import com.samacharam.core.Article
import com.samacharam.core.ContentType
import com.samacharam.core.FeedMode
import com.samacharam.core.FeedbackReason
import com.samacharam.core.Layer
import com.samacharam.core.MarkdownExport
import com.samacharam.core.Mission
import com.samacharam.core.ReadingEvent
import com.samacharam.core.ReadingQueue
import com.samacharam.core.app.FeedQuery
import com.samacharam.core.app.FeedRow
import com.samacharam.core.app.Feeds
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Long-press actions for any story, defined once so every list behaves the same. */
fun storyActions(ctx: AppContext, a: Article): List<SheetAction> = listOf(
    SheetAction("Read at ${hostOf(a.url)}", Icons.AutoMirrored.Outlined.OpenInNew) { ctx.openOriginal(a) },
    SheetAction("Mark as read", Icons.Outlined.DoneAll) { ctx.markRead(a.id) },
    SheetAction("More like this", Icons.Outlined.ThumbUp, "Moves similar items up within their layer") { ctx.feedback(a.id, FeedbackReason.Useful) },
    SheetAction("Less like this", Icons.Outlined.ThumbDown, "Moves similar items down within their layer") { ctx.feedback(a.id, FeedbackReason.LessRelevant) },
    SheetAction("Already seen elsewhere", Icons.Outlined.VisibilityOff) { ctx.feedback(a.id, FeedbackReason.Duplicate) },
    SheetAction("Share link", Icons.Outlined.Share) { ctx.share(a) },
)

/** Refreshes sources. Speaks only when something changed; the masthead shows when it last ran. */
fun refreshSources(ctx: AppContext) = ctx.run {
    val r = PhoneLibrary.refresher(ctx.activity).refresh(force = true)
    ctx.library.setMeta("ui.lastRefresh", System.currentTimeMillis().toString())
    when {
        r.inserted > 0 -> "${r.inserted} new item${if (r.inserted == 1) "" else "s"}" + if (r.failed > 0) " · ${r.failed} source${if (r.failed == 1) "" else "s"} unavailable" else ""
        r.failed > 0 -> "${r.failed} source${if (r.failed == 1) "" else "s"} could not be reached. Earlier items are kept."
        else -> null // "Up to date" is shown in the masthead instead of a snackbar
    }
}

private data class TodayData(
    val briefing: ReadingQueue, val more: List<FeedRow>, val missions: List<Mission>,
    val saved: Set<Long>, val seen: Set<Long>, val failing: Int, val activeSources: Int, val lastRefresh: Long?,
)

private fun greeting() = when (LocalTime.now().hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }

@Composable
fun TodayScreen(ctx: AppContext, nav: NavHostController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var mission by rememberSaveable { mutableStateOf<String?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Article?>(null) }
    val now = remember { System.currentTimeMillis() }
    val data by rememberLoaded(ctx, tab, mission) {
        val l = ctx.library
        val queue = Feeds.dailyBriefing(l, now).first
        val inBriefing = queue.articles.map { it.id }.toSet()
        val mode = if (tab == 0) FeedMode.ForYou else FeedMode.Following
        val sources = l.sources().filter { it.enabled }
        TodayData(
            queue,
            Feeds.today(l, FeedQuery(mode, missionId = mission), now).filter { mode == FeedMode.Following || mission != null || it.article.id !in inBriefing },
            Feeds.activeMissions(l, now), l.savedIds(), l.seenIds(), sources.count { it.lastError != null }, sources.size,
            l.meta("ui.lastRefresh")?.toLongOrNull(),
        )
    }
    val d = data
    val status = d?.let { if (it.lastRefresh == null) "Tap refresh to gather the latest" else "Updated ${timeAgo(it.lastRefresh)}" }

    TabPage(
        ctx, kicker = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), title = greeting(), status = status,
        actions = {
            IconButton(onClick = { nav.navigate(Routes.EXPLORE) }) { Icon(Icons.Outlined.Search, contentDescription = "Search", tint = colors.ink) }
            IconButton(onClick = { refreshSources(ctx) }, enabled = !ctx.busy) { Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = colors.ink) }
        },
    ) {
        if (d == null) { item { StorySkeleton() }; return@TabPage }
        if (d.activeSources > 0 && d.failing == d.activeSources) item {
            Banner(Icons.Outlined.CloudOff, "None of your sources could be reached. Saved items are still here.", "Sources") { nav.navigate(Routes.SOURCES) }
        } else if (d.failing > 0) item {
            Banner(Icons.Outlined.CloudOff, "${d.failing} of ${d.activeSources} sources unavailable", "Review") { nav.navigate(Routes.SOURCES) }
        }

        // The briefing is the primary daily path: its action sits directly under the header, above the fold.
        val q = d.briefing
        val readCount = q.articles.count { it.id in d.seen }
        item(key = "briefing-label") {
            SectionLabel("Your briefing", trailing = {
                if (q.articles.isNotEmpty()) Text("$readCount of ${q.articles.size} read · ${q.minutes} min", style = TypeRole.meta, color = colors.inkMuted, modifier = Modifier.padding(end = Space.m))
            })
        }
        if (q.articles.isEmpty()) item(key = "caught-up") {
            Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.TaskAlt, null, tint = colors.evergreen); Spacer(Modifier.width(Space.s))
                    Text("You're caught up", style = TypeRole.headline, color = colors.ink)
                }
                Text("Nothing unread fits your interests right now. New stories arrive as your sources publish.", style = TypeRole.ui, color = colors.inkMuted)
            }
        } else {
            item(key = "briefing-start") {
                Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    LinearProgressIndicator(
                        progress = { readCount.toFloat() / q.articles.size }, color = colors.saffron, trackColor = colors.rule,
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                            .semantics { contentDescription = "$readCount of ${q.articles.size} briefing stories read" },
                    )
                    val next = q.articles.firstOrNull { it.id !in d.seen }
                    PrimaryButton(
                        when { next == null -> "Briefing finished · read again"; readCount == 0 -> "Start briefing"; else -> "Continue · ${readCount + 1} of ${q.articles.size}" },
                        { nav.navigate(Routes.article((next ?: q.articles.first()).id)) }, Modifier.fillMaxWidth(),
                    )
                }
            }
            val visible = if (showAll) q.articles else q.articles.take(3)
            items(visible, key = { "b-${it.id}" }) { a ->
                StoryRow(a, a.id in d.saved, a.id !in d.seen, null, onOpen = { nav.navigate(Routes.article(a.id)) }, onToggleSave = { ctx.toggleSave(a.id) },
                    onActions = { sheet = a }, showDek = false, rank = q.articles.indexOf(a) + 1)
            }
            if (q.articles.size > 3) item(key = "briefing-more") {
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s)) {
                    SecondaryButton(if (showAll) "Show fewer" else "All ${q.articles.size} in your briefing", { showAll = !showAll })
                }
            }
        }

        // Everything else.
        item(key = "tabs") {
            Column {
                Spacer(Modifier.height(Space.s))
                TextTabs(listOf("More for you", "Following"), tab, { tab = it; if (it == 1) mission = null })
            }
        }
        if (d.missions.isNotEmpty() && tab == 0) item(key = "missions") {
            LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                items(d.missions, key = { it.id }) { m -> Pill(m.title, mission == m.id, { mission = if (mission == m.id) null else m.id }, Icons.Outlined.Flag) }
            }
        }
        if (d.more.isEmpty()) item(key = "empty") {
            EmptyState(Icons.Outlined.RssFeed, if (tab == 0) "That's everything for now" else "Nothing from your sources yet",
                "Refresh to check your sources, or add more in Explore.") { SecondaryButton("Refresh", { refreshSources(ctx) }, enabled = !ctx.busy) }
        }
        items(d.more, key = { "f-${it.article.id}" }) { row ->
            StoryRow(row.article, row.article.id in d.saved, row.article.id !in d.seen, if (tab == 0) row.reason.removeSuffix(".") else null,
                onOpen = { nav.navigate(Routes.article(row.article.id)) }, onToggleSave = { ctx.toggleSave(row.article.id) }, onActions = { sheet = row.article })
        }
    }
    sheet?.let { a -> ActionSheet(a.title, storyActions(ctx, a), { sheet = null }, subtitle = a.sourceName.ifBlank { hostOf(a.url) }) }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s).clip(RoundedCornerShape(Radius.control))
            .background(colors.paperSunk).heightIn(min = 52.dp).padding(horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = colors.inkMuted)
        Spacer(Modifier.width(Space.m))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Titles, excerpts, authors", style = TypeRole.ui, color = colors.inkMuted)
            BasicTextField(value, onChange, singleLine = true, textStyle = TypeRole.ui.copy(color = colors.ink), cursorBrush = SolidColor(colors.evergreen),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search" })
        }
        if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Clear, contentDescription = "Clear search", tint = colors.inkMuted) }
    }
}

@Composable
fun ExploreScreen(ctx: AppContext, nav: NavHostController) {
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf<ContentType?>(null) }
    var layer by rememberSaveable { mutableStateOf<Layer?>(null) }
    var topic by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<Article?>(null) }
    val now = remember { System.currentTimeMillis() }
    val filtering = query.isNotBlank() || type != null || layer != null || topic != null
    val results by rememberLoaded(ctx, query, type, layer, topic) {
        if (!filtering) null else Feeds.today(ctx.library, FeedQuery(query = query, type = type, layer = layer), now).filter { topic == null || topic in it.article.topics }
    }
    val directory by rememberLoaded(ctx) {
        val counts = ctx.library.articles().flatMap { it.topics }.groupingBy { it }.eachCount()
        ctx.library.profile().topics.entries.filter { it.value != Layer.Muted }
            .sortedWith(compareByDescending<Map.Entry<String, Layer>> { it.value.weight }.thenBy { it.key })
            .map { Triple(it.key, it.value, counts[it.key] ?: 0) }
    }
    val marks by rememberLoaded(ctx) { ctx.library.savedIds() to ctx.library.seenIds() }

    TabPage(ctx, kicker = "Search and browse", title = "Explore") {
        item(key = "search") { SearchField(query) { query = it } }
        item(key = "filters") {
            LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                items(listOf(ContentType.Paper to "Papers", ContentType.Blog to "Blogs", ContentType.News to "News", ContentType.Article to "Saved links")) { (t, label) ->
                    Pill(label, type == t, { type = if (type == t) null else t })
                }
                items(listOf(Layer.Core, Layer.Near, Layer.Adjacent, Layer.Discovery)) { l -> Pill(l.name, layer == l, { layer = if (layer == l) null else l }) }
                topic?.let { t -> item { Pill("Topic: $t", true, { topic = null }, Icons.Outlined.Clear) } }
            }
        }
        if (!filtering) {
            item(key = "dir-label") { SectionLabel("Your topics") }
            items(directory.orEmpty(), key = { "t-${it.first}" }) { (name, l, count) ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Browse $name") { topic = name }.padding(horizontal = Space.gutter, vertical = Space.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LayerMark(l, 12.dp); Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = TypeRole.story, color = colors.ink)
                        Text("$count item${if (count == 1) "" else "s"} · ${l.name}", style = TypeRole.meta, color = colors.inkMuted)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = colors.inkFaint)
                }
                Hairline()
            }
            item(key = "sources") {
                SectionLabel("Sources")
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.navigate(Routes.SOURCES) }.padding(horizontal = Space.gutter, vertical = Space.l),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.RssFeed, null, tint = colors.inkMuted); Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Text("Manage sources", style = TypeRole.title, color = colors.ink)
                        Text("Add feeds, check their health, pause or unsubscribe", style = TypeRole.meta, color = colors.inkMuted)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = colors.inkFaint)
                }
            }
        } else {
            val list = results
            if (list == null) item { StorySkeleton(3) }
            else {
                item(key = "count") { SectionLabel("${list.size} result${if (list.size == 1) "" else "s"}") }
                if (list.isEmpty()) item(key = "none") { EmptyState(Icons.Outlined.Search, "No matches", "Try other words, or remove a filter.") }
                items(list, key = { "r-${it.article.id}" }) { row ->
                    val (saved, seen) = marks ?: (emptySet<Long>() to emptySet())
                    StoryRow(row.article, row.article.id in saved, row.article.id !in seen, row.reason.removeSuffix("."),
                        onOpen = { nav.navigate(Routes.article(row.article.id)) }, onToggleSave = { ctx.toggleSave(row.article.id) }, onActions = { sheet = row.article })
                }
            }
        }
    }
    sheet?.let { a -> ActionSheet(a.title, storyActions(ctx, a), { sheet = null }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(ctx: AppContext, nav: NavHostController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var byTitle by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Article?>(null) }
    val saved by rememberLoaded(ctx, byTitle) { ctx.library.savedItems().let { s -> if (byTitle) s.sortedBy { it.article.title.lowercase() } else s } }
    val seen by rememberLoaded(ctx) { ctx.library.seenIds() }
    val highlights by rememberLoaded(ctx) { ctx.library.highlightsWithUrls().map { (url, h) -> Triple(url, h, ctx.library.articleIdForUrl(url)) }.sortedByDescending { it.second.created } }
    val history by rememberLoaded(ctx) { ctx.library.historyEntries() }

    TabPage(ctx, kicker = "Kept for later", title = "Library", actions = {
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Library options", tint = colors.ink) }
            DropdownMenu(menu, { menu = false }) {
                MenuRow(if (byTitle) "Sort by date saved" else "Sort by title", Icons.AutoMirrored.Outlined.Sort) { menu = false; byTitle = !byTitle }
                MenuRow("Export notes as Markdown", Icons.Outlined.FileDownload) {
                    menu = false
                    ctx.run {
                        val files = MarkdownExport.export(ctx.library.savedItems())
                        if (files.isEmpty()) return@run "Save something first."
                        val text = files.entries.joinToString("\n\n---\n\n") { (path, body) -> "<!-- $path -->\n$body" }
                        ctx.activity.runOnUiThread { ctx.shareText("samacha·RAM notes", text) }
                        null
                    }
                }
            }
        }
    }) {
        stickyHeader(key = "tabs") {
            Box(Modifier.background(colors.paper)) {
                TextTabs(listOf("Saved" + (saved?.size?.let { " $it" } ?: ""), "Highlights", "History"), tab, { tab = it })
            }
        }
        when (tab) {
            0 -> {
                val list = saved
                if (list == null) item { StorySkeleton(3) }
                else if (list.isEmpty()) item {
                    EmptyState(Icons.Outlined.BookmarkBorder, "Nothing saved yet", "Tap the bookmark on any story, or share a link from your browser to samacha·RAM.")
                }
                else items(list, key = { "s-${it.article.id}" }) { item ->
                    Column {
                        StoryRow(item.article, true, item.article.id !in seen.orEmpty(), "Saved ${timeAgo(item.savedAt)}",
                            onOpen = { nav.navigate(Routes.article(item.article.id)) }, onToggleSave = { ctx.toggleSave(item.article.id) },
                            onActions = { sheet = item.article }, showDek = item.note.isBlank())
                        if (item.note.isNotBlank()) {
                            QuoteBlock(item.note, "Your note", Modifier.padding(start = Space.gutter, end = Space.gutter, bottom = Space.l))
                            Hairline()
                        }
                    }
                }
            }
            1 -> {
                val list = highlights
                if (list != null && list.isEmpty()) item { EmptyState(Icons.Outlined.FormatQuote, "No highlights yet", "Passages you keep from articles appear here with their source.") }
                items(list.orEmpty(), key = { "h-${it.second.id}" }) { (url, h, articleId) ->
                    Column(Modifier.fillMaxWidth().clickable(enabled = articleId != null) { articleId?.let { nav.navigate(Routes.article(it)) } }.padding(vertical = Space.l)) {
                        QuoteBlock(h.quote, "${hostOf(url)} · ${timeAgo(h.created)}", Modifier.padding(horizontal = Space.gutter), serif = true)
                        if (h.note.isNotBlank()) Text(h.note, style = TypeRole.ui, color = colors.inkMuted, modifier = Modifier.padding(start = Space.gutter + Space.l, end = Space.gutter, top = Space.s))
                    }
                    Hairline()
                }
            }
            else -> {
                val list = history
                if (list != null && list.isEmpty()) item { EmptyState(Icons.Outlined.History, "No reading history", "Items you open, summarize, or mark as read appear here. You can pause history in Privacy.") }
                list.orEmpty().groupBy { java.time.Instant.ofEpochMilli(it.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }.forEach { (day, entries) ->
                    item(key = "d-$day") {
                        SectionLabel(when (day) { LocalDate.now() -> "Today"; LocalDate.now().minusDays(1) -> "Yesterday"; else -> day.format(DateTimeFormatter.ofPattern("EEEE, d MMM")) })
                    }
                    items(entries, key = { "e-${it.at}-${it.article.id}-${it.event}" }) { e ->
                        Column(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.article(e.article.id)) }.padding(horizontal = Space.gutter, vertical = Space.m)) {
                            Text(e.article.title, style = TypeRole.ui, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${eventLabel(e.event)} · ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(e.at))}",
                                style = TypeRole.meta, color = colors.inkMuted)
                        }
                    }
                }
            }
        }
    }
    sheet?.let { a -> ActionSheet(a.title, storyActions(ctx, a), { sheet = null }) }
}

/** A passage set off with a left rule: notes and highlights. */
@Composable
fun QuoteBlock(text: String, caption: String?, modifier: Modifier = Modifier, serif: Boolean = false) {
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(colors.evergreen))
        Spacer(Modifier.width(Space.m + 1.dp))
        Column {
            Text(text, style = if (serif) TypeRole.body else TypeRole.ui, color = colors.ink, maxLines = 6, overflow = TextOverflow.Ellipsis)
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
