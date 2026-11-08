package com.samacharam.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.samacharam.app.ui.ActionSheet
import com.samacharam.app.ui.Avatar
import com.samacharam.app.ui.Banner
import com.samacharam.app.ui.Cover
import com.samacharam.app.ui.DarkPalette
import com.samacharam.app.ui.EmptyState
import com.samacharam.app.ui.Hairline
import com.samacharam.app.ui.Kicker
import com.samacharam.app.ui.LocalPalette
import com.samacharam.app.ui.Motion
import com.samacharam.app.ui.PrimaryButton
import com.samacharam.app.ui.SaveToggle
import com.samacharam.app.ui.SecondaryButton
import com.samacharam.app.ui.Space
import com.samacharam.app.ui.StoryCircle
import com.samacharam.app.ui.StorySkeleton
import com.samacharam.app.ui.TabBar
import com.samacharam.app.ui.TypeRole
import com.samacharam.app.ui.colors
import com.samacharam.app.ui.sourceLabel
import com.samacharam.app.ui.timeAgo
import com.samacharam.app.ui.typeLabel
import com.samacharam.core.Article
import com.samacharam.core.FeedbackReason
import com.samacharam.core.Layer
import com.samacharam.core.ReadingEvent
import com.samacharam.core.app.FeedQuery
import com.samacharam.core.app.FeedRow
import com.samacharam.core.app.Feeds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class HomeData(
    val briefing: List<Article>, val circles: List<Triple<String, Layer, Int>>, val feed: List<FeedRow>,
    val saved: Set<Long>, val seen: Set<Long>, val failing: Int, val active: Int, val alerts: Int,
)

/** Home: story circles for the briefing and followed topics, then the ranked feed as posts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(ctx: AppContext, nav: NavHostController) {
    var sheet by remember { mutableStateOf<Article?>(null) }
    var pulling by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    val data by rememberLoaded(ctx) {
        val l = ctx.library
        val sources = l.sources().filter { it.enabled }
        val failing = sources.count { it.lastError != null }
        HomeData(
            Feeds.dailyBriefing(l, now).first.articles, topicCircles(l), Feeds.today(l, FeedQuery(), now).take(80),
            l.savedIds(), l.seenIds(), failing, sources.size, failing,
        )
    }
    LaunchedEffect(ctx.busy) { if (!ctx.busy) pulling = false }
    val d = data

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        TabBar("Home", wordmark = true) {
            IconButton(onClick = { nav.navigate(Routes.ACTIVITY) }) {
                Box {
                    Icon(Icons.Outlined.NotificationsNone, contentDescription = "Activity" + if ((d?.alerts ?: 0) > 0) ", needs attention" else "", tint = colors.ink)
                    if ((d?.alerts ?: 0) > 0) Box(Modifier.size(9.dp).align(Alignment.TopEnd).clip(CircleShape).background(colors.saffron).border(1.5.dp, colors.paper, CircleShape))
                }
            }
        }
        BusyLine(ctx)
        PullToRefreshBox(isRefreshing = pulling, onRefresh = { pulling = true; refreshSources(ctx) }, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xxl)) {
                if (d == null) { item { StorySkeleton() }; return@LazyColumn }
                item(key = "circles") {
                    LazyRow(contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        item(key = "briefing") {
                            val unread = d.briefing.any { it.id !in d.seen }
                            StoryCircle("Briefing", unread, { if (d.briefing.isNotEmpty()) nav.navigate(Routes.story(Decks.BRIEFING)) else ctx.message = "You're caught up. Pull down to check for new stories." }) {
                                Avatar("Briefing", 58.dp, filled = true, icon = Icons.Outlined.WbSunny)
                            }
                        }
                        items(d.circles, key = { "c-${it.first}" }) { (topic, _, unread) ->
                            StoryCircle(topic, unread > 0, { nav.navigate(Routes.story(Decks.topic(topic))) }) { Avatar(topic, 58.dp) }
                        }
                    }
                    Hairline(inset = 0.dp)
                }
                if (d.active > 0 && d.failing == d.active) item(key = "offline") {
                    Banner(Icons.Outlined.CloudOff, "None of your sources could be reached. Pull down to retry.", "Sources") { ctx.profileTab = 1; nav.navigate(Routes.PROFILE) }
                }
                if (d.feed.isEmpty()) item(key = "empty") {
                    EmptyState(Icons.Outlined.RssFeed, "Nothing here yet", "Pull down to gather stories from your sources.") {
                        SecondaryButton("Refresh now", { refreshSources(ctx) }, enabled = !ctx.busy)
                    }
                }
                items(d.feed, key = { "p-${it.article.id}" }) { row ->
                    PostCard(
                        row, saved = row.article.id in d.saved, unread = row.article.id !in d.seen,
                        onOpen = { nav.navigate(Routes.article(row.article.id)) },
                        onMore = { sheet = row.article },
                        onAsk = { nav.navigate(Routes.ask(row.article.id)) },
                        ctx = ctx,
                    )
                }
            }
        }
    }
    sheet?.let { a -> ActionSheet(a.title, storyActions(ctx, a), { sheet = null }, subtitle = a.sourceLabel()) }
}

/** One feed post: who published it, a cover, an action row, then the excerpt as a caption. */
@Composable
private fun PostCard(row: FeedRow, saved: Boolean, unread: Boolean, onOpen: () -> Unit, onMore: () -> Unit, onAsk: () -> Unit, ctx: AppContext) {
    val a = row.article
    Column(Modifier.fillMaxWidth().padding(top = Space.l)) {
        Row(Modifier.padding(start = Space.gutter, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Avatar(a.sourceLabel(), 34.dp)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.sourceLabel(), style = TypeRole.title.copy(fontSize = 14.sp), color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Text("  ·  ${timeAgo(a.published ?: a.firstSeen)}", style = TypeRole.meta, color = colors.inkMuted, maxLines = 1)
                    if (unread) { Spacer(Modifier.width(Space.s)); Box(Modifier.size(6.dp).clip(CircleShape).background(colors.saffron).semantics { contentDescription = "Unread" }) }
                }
                Text(row.reason.removeSuffix("."), style = TypeRole.meta, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onMore) { Icon(Icons.Outlined.MoreHoriz, contentDescription = "More actions", tint = colors.ink) }
        }
        Box(Modifier.padding(horizontal = Space.gutter, vertical = Space.s).clickable(role = Role.Button, onClickLabel = "Open story") { onOpen() }) { Cover(a) }
        Row(Modifier.padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { ctx.feedback(a.id, FeedbackReason.Useful) }) { Icon(Icons.Outlined.ThumbUp, contentDescription = "More like this", tint = colors.ink) }
            IconButton(onClick = { ctx.feedback(a.id, FeedbackReason.LessRelevant) }) { Icon(Icons.Outlined.ThumbDown, contentDescription = "Less like this", tint = colors.ink) }
            IconButton(onClick = onAsk) { Icon(Icons.Outlined.AutoAwesome, contentDescription = "Ask about this", tint = colors.ink) }
            IconButton(onClick = { ctx.share(a) }) { Icon(Icons.Outlined.Share, contentDescription = "Share", tint = colors.ink) }
            Spacer(Modifier.weight(1f))
            SaveToggle(saved) { ctx.toggleSave(a.id) }
        }
        if (a.excerpt.isNotBlank()) Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(a.sourceLabel()) }
                append("  "); append(a.excerpt)
            },
            style = TypeRole.ui, color = colors.ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Space.gutter).clickable(onClickLabel = "Open story") { onOpen() },
        )
        Spacer(Modifier.height(Space.m))
        Hairline(inset = 0.dp)
    }
}

/**
 * Full-screen story player. Tap the right side for the next story, the left for the previous one,
 * swipe up (or use the button) to open it. Always dark so the headline is the only bright thing.
 */
@Composable
fun StoryPlayerScreen(ctx: AppContext, nav: NavHostController, deck: String) {
    var loaded by remember { mutableStateOf<Pair<String, List<Article>>?>(null) }
    var saved by remember { mutableStateOf(emptySet<Long>()) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    // Loaded once: marking stories as seen must not reshuffle the deck mid-play.
    LaunchedEffect(deck) {
        loaded = withContext(Dispatchers.IO) { runCatching { Decks.load(ctx.library, deck, System.currentTimeMillis()) }.getOrNull() ?: (Decks.title(deck) to emptyList()) }
    }
    LaunchedEffect(ctx.version) { saved = withContext(Dispatchers.IO) { ctx.library.savedIds() } }
    val stories = loaded?.second.orEmpty()
    val current = stories.getOrNull(index)
    LaunchedEffect(current?.id) { current?.let { a -> withContext(Dispatchers.IO) { runCatching { ctx.library.recordEvent(a.id, ReadingEvent.MetadataOpen, System.currentTimeMillis()) } } } }

    CompositionLocalProvider(LocalPalette provides DarkPalette) {
        Column(Modifier.fillMaxSize().background(colors.paper).statusBarsPadding().navigationBarsPadding()) {
            // Progress segments, one per story.
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.s, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                stories.indices.forEach { i ->
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= index) colors.ink else colors.rule))
                }
            }
            Row(Modifier.padding(start = Space.l, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Avatar(current?.sourceLabel() ?: "·", 34.dp)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text(loaded?.first ?: "", style = TypeRole.title.copy(fontSize = 14.sp), color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (current != null) Text("${index + 1} of ${stories.size} · ${current.sourceLabel()} · ${timeAgo(current.published ?: current.firstSeen)}",
                        style = TypeRole.meta, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Outlined.Close, contentDescription = "Close", tint = colors.ink) }
            }
            if (loaded != null && current == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Nothing new here yet.", style = TypeRole.headline, color = colors.ink)
                }
            } else Box(
                Modifier.weight(1f).fillMaxWidth()
                    .pointerInput(stories.size) {
                        detectTapGestures { o ->
                            if (o.x < size.width / 3f) { if (index > 0) index-- }
                            else if (index < stories.lastIndex) index++ else nav.popBackStack()
                        }
                    }
                    .pointerInput(current?.id) {
                        var dy = 0f
                        detectVerticalDragGestures(onDragStart = { dy = 0f }, onDragEnd = { if (dy < -120f) current?.let { nav.navigate(Routes.article(it.id)) } }) { _, d -> dy += d }
                    },
            ) {
                AnimatedContent(current, transitionSpec = { fadeIn(tween(Motion.STANDARD)) togetherWith fadeOut(tween(Motion.EXIT)) }, label = "story") { a ->
                    if (a != null) Column(Modifier.fillMaxSize().padding(horizontal = Space.xxl), verticalArrangement = Arrangement.Center) {
                        Kicker(listOf(typeLabel(a.type), a.topics.take(2).joinToString(" · ")).filter { it.isNotBlank() }.joinToString(" · "), color = colors.evergreen)
                        Spacer(Modifier.height(Space.m))
                        Text(a.title, style = TypeRole.display.copy(fontSize = 28.sp, lineHeight = 34.sp), color = colors.ink, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        if (a.excerpt.isNotBlank()) {
                            Spacer(Modifier.height(Space.l))
                            Text(a.excerpt, style = TypeRole.body.copy(fontSize = 16.sp, lineHeight = 25.sp), color = colors.inkMuted, maxLines = 7, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(Space.l))
                        Text("${a.minutes} min read", style = TypeRole.meta, color = colors.inkMuted)
                    }
                }
                if (current != null) Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = Space.s).clickable(role = Role.Button, onClickLabel = "Open story") { nav.navigate(Routes.article(current.id)) }
                        .padding(Space.s),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.KeyboardArrowUp, null, tint = colors.inkMuted)
                    Text("Swipe up to read", style = TypeRole.meta, color = colors.inkMuted)
                }
            }
            if (current != null) Row(Modifier.padding(start = Space.l, end = Space.xs, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).border(1.dp, colors.rule, RoundedCornerShape(22.dp))
                        .clickable(role = Role.Button) { nav.navigate(Routes.ask(current.id)) }.padding(horizontal = Space.l),
                    contentAlignment = Alignment.CenterStart,
                ) { Text("Ask about this…", style = TypeRole.ui, color = colors.inkMuted) }
                IconButton(onClick = { ctx.feedback(current.id, FeedbackReason.Useful) }) { Icon(Icons.Outlined.ThumbUp, contentDescription = "More like this", tint = colors.ink) }
                SaveToggle(current.id in saved) { ctx.toggleSave(current.id) }
                IconButton(onClick = { ctx.share(current) }) { Icon(Icons.Outlined.Share, contentDescription = "Share", tint = colors.ink) }
            }
        }
    }
}

private data class ActivityItem(val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val detail: String, val tone: Int, val action: String?, val go: () -> Unit)

/** What changed: the briefing, sources that need attention, mission matches, sync. Built from live state, never stored. */
@Composable
fun ActivityScreen(ctx: AppContext, nav: NavHostController) {
    val now = remember { System.currentTimeMillis() }
    val items by rememberLoaded(ctx) {
        val l = ctx.library
        val out = ArrayList<ActivityItem>()
        val q = Feeds.dailyBriefing(l, now).first
        if (q.articles.isNotEmpty()) out += ActivityItem(Icons.Outlined.WbSunny, "Your briefing is ready",
            "${q.articles.size} stories · ${q.minutes} min", 0, "Open") { nav.navigate(Routes.story(Decks.BRIEFING)) }
        l.sources().filter { it.enabled && it.lastError != null }.forEach { s ->
            out += ActivityItem(Icons.Outlined.ErrorOutline, "${s.name} can't be reached", s.lastError ?: "", 2, "Fix") { ctx.profileTab = 1; nav.navigate(Routes.PROFILE) }
        }
        val dayAgo = now - 24 * 3_600_000L
        val profile = l.profile()
        l.articles().filter { it.firstSeen >= dayAgo }.flatMap { it.topics }.filter { profile.topics[it] != null && profile.topics[it] != Layer.Muted }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).forEach { (t, n) ->
                out += ActivityItem(Icons.Outlined.RssFeed, "$n new in $t", "Since yesterday", 1, null) { nav.navigate(Routes.topic(t)) }
            }
        Feeds.activeMissions(l, now).forEach { m ->
            val n = Feeds.today(l, FeedQuery(missionId = m.id), now).size
            out += ActivityItem(Icons.Outlined.Flag, "Mission “${m.title}”", "$n matching stories · until ${m.expires}", 1, null) { nav.navigate(Routes.story(Decks.mission(m.id))) }
        }
        if (PhoneSync.enabled(l)) {
            val err = l.meta("sync.error")?.ifBlank { null }
            out += ActivityItem(Icons.Outlined.CloudSync, if (err != null) "Sync needs attention" else "Synced with Google Drive",
                err ?: (l.meta("sync.lastSync")?.toLongOrNull()?.let { "Last sync ${timeAgo(it)}" } ?: "Not synced yet"), if (err != null) 2 else 1, null) { nav.navigate(Routes.SYNC) }
        }
        out
    }
    AppPage(ctx, "Activity", nav = nav) { padding ->
        val list = items ?: return@AppPage
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (list.isEmpty()) item { EmptyState(Icons.Outlined.Notifications, "All quiet", "Briefings, source problems, and mission matches show up here.") }
            items(list) { ai ->
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { ai.go() }.padding(horizontal = Space.gutter, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                    val (bg, fg) = when (ai.tone) { 0 -> colors.evergreen to colors.onEvergreen; 2 -> colors.dangerSoft to colors.danger; else -> colors.paperSunk to colors.ink }
                    Box(Modifier.size(44.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) { Icon(ai.icon, null, tint = fg) }
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Text(ai.title, style = TypeRole.title.copy(fontSize = 15.sp), color = colors.ink)
                        Text(ai.detail, style = TypeRole.meta, color = colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    ai.action?.let { label -> Spacer(Modifier.width(Space.s)); PrimaryButton(label, ai.go) }
                }
            }
        }
    }
}

