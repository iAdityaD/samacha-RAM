package com.samacharam.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samacharam.core.Article
import com.samacharam.core.ContentType

/*
 * Social-app building blocks (top bar, avatars, story circles, covers, tiles, icon tabs, counts),
 * drawn only from tokens so they sit in the same "Ink and paper" system as everything else.
 */

/** Background and text color for a cover, by kind of content. Color is never the only signal: the kicker names the type. */
data class CoverStyle(val background: Color, val content: Color, val accent: Color)

@Composable
fun coverStyle(type: ContentType): CoverStyle = when (type) {
    ContentType.Paper -> CoverStyle(colors.evergreenSoft, colors.ink, colors.evergreen)
    ContentType.Blog -> CoverStyle(colors.saffronSoft, colors.ink, colors.inkMuted)
    ContentType.News -> CoverStyle(colors.ink, colors.paper, colors.paperSunk)
    ContentType.Article -> CoverStyle(colors.paperSunk, colors.ink, colors.inkMuted)
}

fun typeIcon(type: ContentType): ImageVector = when (type) {
    ContentType.Paper -> Icons.Outlined.Description
    ContentType.Blog -> Icons.Outlined.RssFeed
    ContentType.News -> Icons.Outlined.Newspaper
    ContentType.Article -> Icons.Outlined.Link
}

fun typeLabel(type: ContentType) = when (type) {
    ContentType.Paper -> "Paper"; ContentType.Blog -> "Blog"; ContentType.News -> "News"; ContentType.Article -> "Link"
}

/** Short label for an avatar: "Hugging Face" → "HF", "NLP" → "NLP", "Software" → "S", "arXiv · AI" → "AA". */
fun initials(name: String): String {
    val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "·"
        words.size == 1 && words[0].length <= 4 -> words[0]
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/** A round avatar with initials. The tint is stable per name so a source always looks the same. */
@Composable
fun Avatar(name: String, size: Dp = 32.dp, filled: Boolean = false, icon: ImageVector? = null) {
    val palette = listOf(colors.evergreenSoft to colors.evergreen, colors.saffronSoft to colors.ink, colors.paperSunk to colors.ink)
    val (bg, fg) = if (filled) colors.evergreen to colors.onEvergreen else palette[Math.floorMod(name.hashCode(), palette.size)]
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(size * 0.5f))
        else Text(initials(name), color = fg, style = TypeRole.meta.copy(fontSize = (size.value * 0.34f).sp, fontWeight = FontWeight.SemiBold))
    }
}

/**
 * Story circle: a saffron ring means something unread is inside, a hairline ring means you're caught up.
 */
@Composable
fun StoryCircle(label: String, unread: Boolean, onClick: () -> Unit, size: Dp = 60.dp, dashed: Boolean = false, content: @Composable () -> Unit) {
    Column(
        Modifier.width(size + 16.dp).clickable(role = Role.Button, onClickLabel = "Open") { onClick() }
            .semantics(mergeDescendants = true) { contentDescription = label + if (unread) ", new stories" else "" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(size + 8.dp).border(if (unread) 2.5.dp else 1.dp, if (unread) colors.saffron else if (dashed) colors.inkFaint else colors.rule, CircleShape).padding(4.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
        Spacer(Modifier.height(Space.xs))
        Text(label, style = TypeRole.meta.copy(fontSize = 11.sp), color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Top bar for tabs: a title (or wordmark) on the left, icon actions on the right. Stays put; no large collapsing header. */
@Composable
fun TabBar(title: String, wordmark: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(colors.paper).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = Space.gutter, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            if (wordmark) Wordmark(Modifier.weight(1f))
            else Text(title, style = TypeRole.headline, color = colors.ink, modifier = Modifier.weight(1f).semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
        }
    }
}

@Composable
fun Wordmark(modifier: Modifier = Modifier) = Row(modifier.semantics(mergeDescendants = true) { heading(); contentDescription = "samacha RAM" }) {
    Text("samacha", style = TypeRole.headline, color = colors.ink)
    Text("·", style = TypeRole.headline, color = colors.saffron)
    Text("RAM", style = TypeRole.headline, color = colors.ink)
}

/** A big cover for a post: the content kind as a kicker, the headline in serif. */
@Composable
fun Cover(article: Article, modifier: Modifier = Modifier) {
    val st = coverStyle(article.type)
    Column(
        modifier.fillMaxWidth().heightIn(min = 168.dp).clip(RoundedCornerShape(Radius.control)).background(st.background).padding(Space.l),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("${typeLabel(article.type)} · ${article.minutes} min".uppercase(), style = TypeRole.kicker, color = st.accent)
        Spacer(Modifier.height(Space.xxl))
        Text(article.title, style = TypeRole.headline.copy(fontSize = 21.sp, lineHeight = 27.sp), color = st.content, maxLines = 5, overflow = TextOverflow.Ellipsis)
    }
}

/** Square-ish grid tile used by Explore. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Tile(article: Article, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val st = coverStyle(article.type)
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier.fillMaxWidth().aspectRatio(0.8f).background(st.background)
            .combinedClickable(role = Role.Button, onClickLabel = "Open", onLongClickLabel = "More actions",
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress() }, onClick = onOpen)
            .semantics { contentDescription = "${typeLabel(article.type)}: ${article.title}, ${article.sourceLabel()}" }
            .padding(Space.s),
    ) {
        Icon(typeIcon(article.type), null, tint = st.accent, modifier = Modifier.size(14.dp).align(Alignment.TopEnd))
        Text(article.title, style = TypeRole.story.copy(fontSize = 13.sp, lineHeight = 16.sp), color = st.content, maxLines = 5, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart))
    }
}

/** A count over a label, as on a profile header. */
@Composable
fun Count(value: String, label: String, modifier: Modifier = Modifier) =
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = TypeRole.title.copy(fontSize = 18.sp), color = colors.ink)
        Text(label, style = TypeRole.meta, color = colors.inkMuted)
    }

/** Icon-only tabs with an underline, for switching views on one screen. Each icon carries a spoken label. */
@Composable
fun IconTabs(tabs: List<Pair<ImageVector, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, (icon, label) ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).clickable(role = Role.Tab) { onSelect(i) }.semantics { contentDescription = label; this.selected = on },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(icon, null, tint = if (on) colors.ink else colors.inkFaint, modifier = Modifier.padding(vertical = Space.m))
                    Box(Modifier.fillMaxWidth().height(1.5.dp).background(if (on) colors.ink else Color.Transparent))
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.rule)
    }
}

/** Compact secondary button used in rows of two or three (profile, topic page). */
@Composable
fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) =
    Box(
        modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(if (primary) colors.evergreen else colors.paperSunk)
            .clickable(enabled = enabled, role = Role.Button) { onClick() }.padding(horizontal = Space.m),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = TypeRole.title.copy(fontSize = 14.sp), color = if (primary) colors.onEvergreen else colors.ink, maxLines = 1) }
