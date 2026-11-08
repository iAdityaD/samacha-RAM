package com.samacharam.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.samacharam.app.ui.ArticleActions
import com.samacharam.app.ui.ArticleCard
import com.samacharam.app.ui.EmptyState
import com.samacharam.app.ui.LayerPill
import com.samacharam.app.ui.MenuRow
import com.samacharam.app.ui.SectionHeader
import com.samacharam.app.ui.timeAgo
import com.samacharam.core.Article
import com.samacharam.core.ContentType
import com.samacharam.core.FeedMode
import com.samacharam.core.FeedbackReason
import com.samacharam.core.Layer
import com.samacharam.core.MarkdownExport
import com.samacharam.core.ReadingEvent
import com.samacharam.core.app.FeedQuery
import com.samacharam.core.app.Feeds
import java.time.LocalTime
import java.time.format.DateTimeFormatter

fun actionsFor(ctx: AppContext, nav: NavHostController, a: Article) = ArticleActions(
    onOpen = { nav.navigate(Routes.article(a.id)) },
    onToggleSave = { ctx.toggleSave(a.id) },
    onMarkRead = { ctx.markRead(a.id) },
    onMore = { ctx.feedback(a.id, FeedbackReason.Useful) },
    onLess = { ctx.feedback(a.id, FeedbackReason.LessRelevant) },
    onShare = { ctx.share(a) },
    onOriginal = { ctx.openOriginal(a) },
)

private fun greeting(): String = when (LocalTime.now().hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }

private fun refresh(ctx: AppContext) = ctx.run { PhoneLibrary.summary(PhoneLibrary.refresher(ctx.activity).refresh(force = true)) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(ctx: AppContext, nav: NavHostController) {
    var mode by rememberSaveable { mutableStateOf(FeedMode.ForYou) }
    var mission by rememberSaveable { mutableStateOf<String?>(null) }
    val now = remember { System.currentTimeMillis() }
    val briefing by rememberLoaded(ctx) { Feeds.dailyBriefing(ctx.library, now).first }
    val missions by rememberLoaded(ctx) { Feeds.activeMissions(ctx.library, now) }
    val rows by rememberLoaded(ctx, mode, mission) { Feeds.today(ctx.library, FeedQuery(mode, missionId = mission), now) }
    val saved by rememberLoaded(ctx) { ctx.library.savedIds() }

    AppPage(ctx, "Today", actions = {
        IconButton(onClick = { nav.navigate(Routes.EXPLORE) }) { Icon(Icons.Outlined.Search, contentDescription = "Search") }
        IconButton(onClick = { refresh(ctx) }, enabled = !ctx.busy) { Icon(Icons.Outlined.Refresh, contentDescription = "Refresh") }
    }) { padding ->
        PullToRefreshBox(isRefreshing = ctx.busy, onRefresh = { refresh(ctx) }, modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("${greeting()} · ${java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM"))}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                briefing?.let { queue ->
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (queue.articles.isEmpty()) Icons.Outlined.TaskAlt else Icons.Outlined.AutoAwesome, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Your briefing", style = MaterialTheme.typography.titleMedium)
                                }
                                if (queue.articles.isEmpty()) {
                                    Text("You're caught up.", style = MaterialTheme.typography.headlineSmall)
                                    Text("Pull to refresh, or widen your interests for more.", style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    Text("${queue.articles.size} items · ${queue.minutes} of ${queue.budgetMinutes} min", style = MaterialTheme.typography.headlineSmall)
                                    LinearProgressIndicator(progress = { queue.minutes.toFloat() / queue.budgetMinutes.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                                    queue.articles.take(3).forEachIndexed { i, a ->
                                        Text("${i + 1}. ${a.title}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.fillMaxWidth().clickable { nav.navigate(Routes.article(a.id)) }.padding(vertical = 2.dp))
                                    }
                                    Button(onClick = { nav.navigate(Routes.article(queue.articles.first().id)) }) { Text("Start reading") }
                                }
                            }
                        }
                    }
                }
                item {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(FeedMode.ForYou to "For you", FeedMode.Following to "Following").forEachIndexed { i, (m, label) ->
                            SegmentedButton(selected = mode == m && mission == null, onClick = { mode = m; mission = null }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                        }
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(missions.orEmpty(), key = { it.id }) { m ->
                            FilterChip(mission == m.id, { mission = if (mission == m.id) null else m.id; mode = FeedMode.ForYou }, { Text(m.title) },
                                leadingIcon = { Icon(Icons.Outlined.Flag, null, Modifier.padding(0.dp)) })
                        }
                        item { FilterChip(false, { nav.navigate(Routes.MISSIONS) }, { Text("New mission") }, leadingIcon = { Icon(Icons.Outlined.Add, null) }) }
                    }
                }
                val list = rows
                when {
                    list == null -> item { Spacer(Modifier.height(1.dp)) }
                    list.isEmpty() -> item {
                        EmptyState(Icons.Outlined.RssFeed, "Nothing here yet", "Refresh your sources to gather the latest papers and posts.") {
                            Button(onClick = { refresh(ctx) }, enabled = !ctx.busy) { Text("Refresh sources") }
                        }
                    }
                    else -> {
                        item { SectionHeader(if (mode == FeedMode.Following) "Latest from your sources" else "Picked for your interests", Modifier.padding(start = 0.dp)) }
                        items(list, key = { it.article.id }) { row ->
                            ArticleCard(row.article, row.article.id in saved.orEmpty(), row.reason, row.layer.takeIf { mode == FeedMode.ForYou }, actionsFor(ctx, nav, row.article))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(ctx: AppContext, nav: NavHostController) {
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf<ContentType?>(null) }
    var layer by rememberSaveable { mutableStateOf<Layer?>(null) }
    var topic by rememberSaveable { mutableStateOf<String?>(null) }
    val now = remember { System.currentTimeMillis() }
    val filtering = query.isNotBlank() || type != null || layer != null || topic != null
    val rows by rememberLoaded(ctx, query, type, layer, topic) {
        if (!filtering) null else Feeds.today(ctx.library, FeedQuery(query = query, type = type, layer = layer), now).filter { topic == null || topic in it.article.topics }
    }
    val topics by rememberLoaded(ctx) {
        val counts = ctx.library.articles().flatMap { it.topics }.groupingBy { it }.eachCount()
        ctx.library.profile().topics.entries.filter { it.value != Layer.Muted }.sortedByDescending { it.value.weight }.map { Triple(it.key, it.value, counts[it.key] ?: 0) }
    }
    val saved by rememberLoaded(ctx) { ctx.library.savedIds() }

    AppPage(ctx, "Explore") { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(
                    query, { query = it }, placeholder = { Text("Search titles, excerpts, authors") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Clear, contentDescription = "Clear search") } },
                    shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(ContentType.Paper to "Papers", ContentType.Blog to "Blogs", ContentType.News to "News", ContentType.Article to "Links")) { (t, label) ->
                        FilterChip(type == t, { type = if (type == t) null else t }, { Text(label) })
                    }
                    items(listOf(Layer.Core, Layer.Near, Layer.Adjacent, Layer.Discovery)) { l ->
                        FilterChip(layer == l, { layer = if (layer == l) null else l }, { Text(l.name) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer))
                    }
                }
            }
            topic?.let { t ->
                item { FilterChip(true, { topic = null }, { Text("Topic: $t") }, trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Clear topic") }) }
            }
            if (!filtering) {
                item { SectionHeader("Browse your topics", Modifier.padding(start = 0.dp)) }
                items(topics.orEmpty(), key = { it.first }) { (name, l, count) ->
                    Card(onClick = { topic = name }, modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = { Text("$count item${if (count == 1) "" else "s"} in your library") },
                            trailingContent = { LayerPill(l) },
                        )
                    }
                }
                item {
                    Card(onClick = { nav.navigate(Routes.SOURCES) }, modifier = Modifier.fillMaxWidth()) {
                        ListItem(headlineContent = { Text("Manage sources") }, supportingContent = { Text("Add feeds, check their health, pause or unsubscribe") },
                            leadingContent = { Icon(Icons.Outlined.RssFeed, null) })
                    }
                }
            } else {
                val list = rows
                if (list != null && list.isEmpty()) item { EmptyState(Icons.Outlined.Search, "No matches", "Try other words or remove a filter.") }
                if (list != null) {
                    item { Text("${list.size} result${if (list.size == 1) "" else "s"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(list, key = { it.article.id }) { row ->
                        ArticleCard(row.article, row.article.id in saved.orEmpty(), row.reason, row.layer, actionsFor(ctx, nav, row.article))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(ctx: AppContext, nav: NavHostController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sortByTitle by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val saved by rememberLoaded(ctx, sortByTitle) { ctx.library.savedItems().let { s -> if (sortByTitle) s.sortedBy { it.article.title.lowercase() } else s } }
    val highlights by rememberLoaded(ctx) { ctx.library.highlightsWithUrls().map { (url, h) -> Triple(url, h, ctx.library.articleIdForUrl(url)) }.sortedByDescending { it.second.created } }
    val history by rememberLoaded(ctx) { ctx.library.historyEntries() }

    AppPage(ctx, "Library", actions = {
        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort") }
        DropdownMenu(sortMenu, { sortMenu = false }) {
            MenuRow("Recently saved", Icons.Outlined.History) { sortMenu = false; sortByTitle = false }
            MenuRow("Title A–Z", Icons.AutoMirrored.Outlined.Sort) { sortMenu = false; sortByTitle = true }
        }
        IconButton(onClick = {
            ctx.run {
                val files = MarkdownExport.export(ctx.library.savedItems())
                if (files.isEmpty()) return@run "Save something first."
                val text = files.entries.joinToString("\n\n---\n\n") { (path, body) -> "<!-- $path -->\n$body" }
                ctx.activity.runOnUiThread { ctx.shareText("samacha·RAM notes", text) }
                null
            }
        }) { Icon(Icons.Outlined.FileDownload, contentDescription = "Export as Markdown") }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Saved", "Highlights", "History").forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) }
            }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                when (tab) {
                    0 -> {
                        val list = saved
                        if (list != null && list.isEmpty()) item {
                            EmptyState(Icons.Outlined.Search, "Nothing saved yet", "Tap the bookmark on any item, or share a link from your browser to samacha·RAM.")
                        }
                        items(list.orEmpty(), key = { it.article.id }) { item ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ArticleCard(item.article, true, "Saved ${timeAgo(item.savedAt)}", null, actionsFor(ctx, nav, item.article))
                                if (item.note.isNotBlank()) Text("Note: " + item.note, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                            }
                        }
                    }
                    1 -> {
                        val list = highlights
                        if (list != null && list.isEmpty()) item { EmptyState(Icons.Outlined.FormatQuote, "No highlights", "Highlights you keep from articles appear here, with their source.") }
                        items(list.orEmpty(), key = { it.second.id }) { (url, h, articleId) ->
                            Card(onClick = { articleId?.let { nav.navigate(Routes.article(it)) } }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Outlined.FormatQuote, null, tint = MaterialTheme.colorScheme.primary)
                                    Text(h.quote, style = MaterialTheme.typography.bodyLarge)
                                    if (h.note.isNotBlank()) Text(h.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${com.samacharam.app.ui.hostOf(url)} · ${timeAgo(h.created)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    else -> {
                        val list = history
                        if (list != null && list.isEmpty()) item { EmptyState(Icons.Outlined.History, "No reading history", "Items you open, summarize, or mark as read appear here. You can pause history in Privacy.") }
                        items(list.orEmpty()) { e ->
                            ListItem(
                                headlineContent = { Text(e.article.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text("${eventLabel(e.event)} · ${timeAgo(e.at)}") },
                                leadingContent = { Icon(Icons.AutoMirrored.Outlined.Article, null) },
                                modifier = Modifier.clickable { nav.navigate(Routes.article(e.article.id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

fun eventLabel(e: ReadingEvent) = when (e) {
    ReadingEvent.MetadataOpen -> "Opened"
    ReadingEvent.SummaryOpen -> "Summary viewed"
    ReadingEvent.OriginalOpen -> "Original opened"
    ReadingEvent.MarkRead -> "Marked read"
    ReadingEvent.DocumentOpen -> "Document opened"
}

