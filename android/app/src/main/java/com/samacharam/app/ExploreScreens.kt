package com.samacharam.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.samacharam.app.ui.ActionSheet
import com.samacharam.app.ui.Avatar
import com.samacharam.app.ui.ConfirmDialog
import com.samacharam.app.ui.Count
import com.samacharam.app.ui.EmptyState
import com.samacharam.app.ui.Kicker
import com.samacharam.app.ui.LayerMark
import com.samacharam.app.ui.Pill
import com.samacharam.app.ui.SheetAction
import com.samacharam.app.ui.SmallButton
import com.samacharam.app.ui.Space
import com.samacharam.app.ui.StoryRow
import com.samacharam.app.ui.StorySkeleton
import com.samacharam.app.ui.TextTabs
import com.samacharam.app.ui.Tile
import com.samacharam.app.ui.TypeRole
import com.samacharam.app.ui.colors
import com.samacharam.core.Article
import com.samacharam.core.ContentType
import com.samacharam.core.FeedMode
import com.samacharam.core.Layer
import com.samacharam.core.app.FeedQuery
import com.samacharam.core.app.Feeds
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Explore: everything you can reach, newest first, as a grid. Search and type filters live only here. */
@Composable
fun ExploreScreen(ctx: AppContext, nav: NavHostController) {
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf<ContentType?>(null) }
    var sheet by remember { mutableStateOf<Article?>(null) }
    val now = remember { System.currentTimeMillis() }
    val items by rememberLoaded(ctx, query, type) {
        Feeds.today(ctx.library, FeedQuery(mode = FeedMode.Following, query = query, type = type), now).map { it.article }.take(300)
    }
    val topics by rememberLoaded(ctx) {
        ctx.library.profile().topics.filterValues { it != Layer.Muted }.entries.sortedWith(compareByDescending<Map.Entry<String, Layer>> { it.value.weight }.thenBy { it.key })
            .map { it.key to it.value }
    }

    LazyVerticalGrid(
        GridCells.Fixed(3), Modifier.fillMaxSize().background(colors.paper),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = Space.xxl),
    ) {
        item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.statusBarsPadding().padding(start = Space.gutter, end = Space.gutter, top = Space.s)) {
                SearchField(query, "Search papers, posts and news", { query = it })
            }
        }
        item(key = "types", span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                item { Pill("All", type == null, { type = null }) }
                items(listOf(ContentType.Paper to "Papers", ContentType.Blog to "Blogs", ContentType.News to "News")) { (t, label) ->
                    Pill(label, type == t, { type = if (type == t) null else t })
                }
            }
        }
        if (query.isBlank() && !topics.isNullOrEmpty()) item(key = "topics", span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, bottom = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                items(topics.orEmpty(), key = { it.first }) { (t, _) -> Pill(t, false, { nav.navigate(Routes.topic(t)) }, Icons.Outlined.Tag) }
            }
        }
        val list = items
        when {
            list == null -> item(span = { GridItemSpan(maxLineSpan) }) { StorySkeleton(3) }
            list.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                if (query.isNotBlank() || type != null) EmptyState(Icons.Outlined.Search, "No matches", "Try other words, or choose All.")
                else EmptyState(Icons.Outlined.Search, "Nothing to explore yet", "Pull down on Home to gather stories from your sources.")
            }
            else -> items(list, key = { it.id }) { a -> Tile(a, { nav.navigate(Routes.article(a.id)) }, { sheet = a }) }
        }
    }
    sheet?.let { a -> ActionSheet(a.title, storyActions(ctx, a), { sheet = null }) }
}

private data class TopicData(val layer: Layer?, val items: List<Article>, val sources: Int, val read: Int, val months: List<Int>, val saved: Set<Long>, val seen: Set<Long>)

/** One topic: its numbers, its stories, and the one place to change its layer. */
@Composable
fun TopicScreen(ctx: AppContext, nav: NavHostController, name: String) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var choosing by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Article?>(null) }
    val data by rememberLoaded(ctx, name) {
        val l = ctx.library
        val all = l.articles().filter { name in it.topics }
        val seen = l.seenIds()
        val zone = ZoneId.systemDefault()
        val thisMonth = YearMonth.now()
        val byMonth = all.groupingBy { YearMonth.from(Instant.ofEpochMilli(it.published ?: it.firstSeen).atZone(zone)) }.eachCount()
        TopicData(
            l.profile().topics[name], all.sortedByDescending { it.published ?: it.firstSeen },
            all.map { it.sourceKey }.distinct().size, all.count { it.id in seen },
            (11 downTo 0).map { byMonth[thisMonth.minusMonths(it.toLong())] ?: 0 }, l.savedIds(), seen,
        )
    }
    AppPage(ctx, name, nav = nav) { padding ->
        val d = data ?: return@AppPage
        val shown = when (tab) { 1 -> d.items.filter { it.type == ContentType.Paper }; 2 -> d.items.filter { it.id !in d.seen }; else -> d.items }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = Space.xxl)) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.m)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name, 76.dp)
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Count("${d.items.size}", "stories"); Count("${d.sources}", "sources"); Count("${d.read}", "you read")
                        }
                    }
                    Spacer(Modifier.height(Space.m))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        d.layer?.let { LayerMark(it, 12.dp); Spacer(Modifier.width(Space.s)) }
                        Text(d.layer?.let { "${it.name} interest · ${LAYER_HELP[it]}" } ?: "Not one of your topics", style = TypeRole.meta, color = colors.inkMuted)
                    }
                    Spacer(Modifier.height(Space.m))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        SmallButton(if (d.layer == null || d.layer == Layer.Muted) "Follow" else "${d.layer.name} ▾", { choosing = true }, Modifier.weight(1f), primary = d.layer != null && d.layer != Layer.Muted)
                        SmallButton("Start a mission", { nav.navigate(Routes.MISSIONS) }, Modifier.weight(1f))
                    }
                    if (d.months.any { it > 0 }) {
                        Spacer(Modifier.height(Space.l))
                        Kicker("Stories per month · last 12 months")
                        Spacer(Modifier.height(Space.s))
                        val max = d.months.max().coerceAtLeast(1)
                        Row(Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = "Stories per month: ${d.months.joinToString()}" },
                            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                            d.months.forEachIndexed { i, n ->
                                Box(Modifier.weight(1f).fillMaxHeight((n.toFloat() / max).coerceAtLeast(0.04f)).background(if (i == 11) colors.evergreen else colors.evergreenSoft))
                            }
                        }
                    }
                }
                TextTabs(listOf("All", "Papers", "Unread"), tab, { tab = it })
            }
            if (shown.isEmpty()) item { EmptyState(Icons.Outlined.Tag, "No stories here", "New stories on $name appear as your sources publish them.") }
            items(shown.take(100), key = { it.id }) { a ->
                StoryRow(a, a.id in d.saved, a.id !in d.seen, null, onOpen = { nav.navigate(Routes.article(a.id)) }, onToggleSave = { ctx.toggleSave(a.id) }, onActions = { sheet = a })
            }
        }
        if (choosing) ActionSheet(name, Layer.entries.map { l ->
            SheetAction(l.name, Icons.Outlined.CheckCircle, LAYER_HELP[l], selected = l == d.layer) { ctx.quick { ctx.library.setLayer(name, l); null } }
        } + listOfNotNull(d.layer?.let {
            SheetAction("Remove topic", Icons.Outlined.Delete, "Removes it from your interests and item tags", destructive = true) { removing = true }
        }), { choosing = false }, subtitle = "Choose a layer")
    }
    if (removing) ConfirmDialog("Remove $name?", "It is removed from your interests and from item tags. Saved items stay.", "Remove", destructive = true,
        onDismiss = { removing = false }) { ctx.quick { ctx.library.removeTopic(name); "Removed $name" }; nav.popBackStack() }
    sheet?.let { a -> ActionSheet(a.title, storyActions(ctx, a), { sheet = null }) }
}

