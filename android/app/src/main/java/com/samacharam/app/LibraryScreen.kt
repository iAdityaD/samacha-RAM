package com.samacharam.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.samacharam.app.ui.ActionSheet
import com.samacharam.app.ui.EmptyState
import com.samacharam.app.ui.Hairline
import com.samacharam.app.ui.Kicker
import com.samacharam.app.ui.Pill
import com.samacharam.app.ui.SectionLabel
import com.samacharam.app.ui.Space
import com.samacharam.app.ui.StoryRow
import com.samacharam.app.ui.StorySkeleton
import com.samacharam.app.ui.TabBar
import com.samacharam.app.ui.TypeRole
import com.samacharam.app.ui.colors
import com.samacharam.app.ui.coverStyle
import com.samacharam.app.ui.hostOf
import com.samacharam.app.ui.sourceLabel
import com.samacharam.app.ui.timeAgo
import com.samacharam.core.Article
import com.samacharam.core.ContentType
import com.samacharam.core.db.SavedItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Smart collections over saved items. They need no setup and never go stale. */
private enum class Shelf(val label: String, val keep: (SavedItem) -> Boolean) {
    All("All saved", { true }),
    Papers("Papers", { it.article.type == ContentType.Paper }),
    Posts("Blogs and news", { it.article.type == ContentType.Blog || it.article.type == ContentType.News }),
    Links("Shared links", { it.article.type == ContentType.Article }),
}

/** Library: what you kept. Saved items, your highlights, your notes, and what you've read. */
@Composable
fun LibraryScreen(ctx: AppContext, nav: NavHostController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var shelf by rememberSaveable { mutableStateOf(Shelf.All) }
    var byTitle by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Article?>(null) }
    val saved by rememberLoaded(ctx, byTitle) { ctx.library.savedItems().let { s -> if (byTitle) s.sortedBy { it.article.title.lowercase() } else s } }
    val seen by rememberLoaded(ctx) { ctx.library.seenIds() }
    val highlights by rememberLoaded(ctx) {
        ctx.library.highlightsWithUrls().map { (url, h) -> Triple(url, h, ctx.library.articleIdForUrl(url)) }.sortedByDescending { it.second.created }
    }
    val history by rememberLoaded(ctx) { ctx.library.historyEntries() }

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        TabBar("Library") {
            if (tab == 0) IconButton(onClick = { byTitle = !byTitle }) {
                Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = if (byTitle) "Sort by date saved" else "Sort by title", tint = colors.ink)
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            val tabs = listOf("Saved" + (saved?.size?.let { " $it" } ?: ""), "Highlights", "Notes", "History")
            items(tabs.size) { i -> Pill(tabs[i], tab == i, { tab = i }) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = Space.xxl)) {
            when (tab) {
                0 -> {
                    val list = saved
                    if (list == null) item { StorySkeleton(3) }
                    else if (list.isEmpty()) item {
                        EmptyState(Icons.Outlined.BookmarkBorder, "Nothing saved yet", "Tap the bookmark on any story, or share a link from your browser to samacha·RAM.")
                    } else {
                        item(key = "shelves") {
                            Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                                Shelf.entries.filter { s -> s == Shelf.All || list.any(s.keep) }.chunked(2).forEach { pair ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                                        pair.forEach { s -> ShelfCard(s.label, list.filter(s.keep), shelf == s, Modifier.weight(1f)) { shelf = s } }
                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        item(key = "shelf-label") { SectionLabel(shelf.label) }
                        items(list.filter(shelf.keep), key = { "s-${it.article.id}" }) { item ->
                            StoryRow(item.article, true, item.article.id !in seen.orEmpty(), "Saved ${timeAgo(item.savedAt)}" + if (item.note.isNotBlank()) " · note" else "",
                                onOpen = { nav.navigate(Routes.article(item.article.id)) }, onToggleSave = { ctx.toggleSave(item.article.id) }, onActions = { sheet = item.article })
                        }
                    }
                }
                1 -> {
                    val list = highlights
                    if (list != null && list.isEmpty()) item { EmptyState(Icons.Outlined.FormatQuote, "No highlights yet", "Passages you keep from stories appear here with their source.") }
                    items(list.orEmpty(), key = { "h-${it.second.id}" }) { (url, h, articleId) ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = articleId != null) { articleId?.let { nav.navigate(Routes.article(it)) } }.padding(vertical = Space.l)) {
                            QuoteBlock(h.quote, "${hostOf(url)} · ${timeAgo(h.created)}", Modifier.padding(horizontal = Space.gutter), serif = true)
                            if (h.note.isNotBlank()) Text("↳ ${h.note}", style = TypeRole.ui, color = colors.inkMuted, modifier = Modifier.padding(start = Space.gutter + Space.l, end = Space.gutter, top = Space.s))
                        }
                        Hairline()
                    }
                }
                2 -> {
                    val list = saved?.filter { it.note.isNotBlank() }
                    if (list != null && list.isEmpty()) item { EmptyState(Icons.Outlined.EditNote, "No notes yet", "Open a saved story and write what matters. Notes stay with the story.") }
                    items(list.orEmpty(), key = { "n-${it.article.id}" }) { item ->
                        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.navigate(Routes.article(item.article.id)) }.padding(horizontal = Space.gutter, vertical = Space.l)) {
                            Kicker("${item.article.sourceLabel()} · ${timeAgo(item.savedAt)}")
                            Spacer(Modifier.height(Space.xs))
                            Text(item.article.title, style = TypeRole.story, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(Space.s))
                            QuoteBlock(item.note, null)
                        }
                        Hairline()
                    }
                }
                else -> {
                    val list = history
                    if (list != null && list.isEmpty()) item { EmptyState(Icons.Outlined.History, "No reading history", "Stories you open, summarize, or mark as read appear here. You can pause history in Settings → Privacy.") }
                    list.orEmpty().groupBy { Instant.ofEpochMilli(it.at).atZone(ZoneId.systemDefault()).toLocalDate() }.forEach { (day, entries) ->
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
    }
    sheet?.let { a -> ActionSheet(a.title, storyActions(ctx, a), { sheet = null }) }
}

/** A collection card: a 2×2 mosaic of its first covers, its name and its count. */
@Composable
private fun ShelfCard(label: String, items: List<SavedItem>, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(role = Role.Tab) { onClick() }.semantics { contentDescription = "$label, ${items.size} items"; this.selected = selected }) {
        Column(
            Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(12.dp))
                .border(if (selected) 2.dp else 0.dp, if (selected) colors.evergreen else colors.paper, RoundedCornerShape(12.dp)),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val covers = items.take(4).map { coverStyle(it.article.type).background } + List(4) { colors.paperSunk }
            listOf(0, 2).forEach { r ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(Modifier.weight(1f).fillMaxSize().background(covers[r])); Box(Modifier.weight(1f).fillMaxSize().background(covers[r + 1]))
                }
            }
        }
        Spacer(Modifier.height(Space.xs))
        Text(label, style = TypeRole.title.copy(fontSize = 14.sp), color = colors.ink, maxLines = 1)
        Text("${items.size} item${if (items.size == 1) "" else "s"}", style = TypeRole.meta, color = colors.inkMuted)
    }
}
