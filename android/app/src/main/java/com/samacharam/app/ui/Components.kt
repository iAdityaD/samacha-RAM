package com.samacharam.app.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.samacharam.core.Article
import com.samacharam.core.Layer

val colors: Palette @Composable get() = LocalPalette.current

fun timeAgo(epochMs: Long?): String =
    epochMs?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString() } ?: "Date unknown"

fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').removePrefix("www.")

fun Article.sourceLabel(): String = sourceName.ifBlank { hostOf(url) }

// ---------- Structure ----------

@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = Space.gutter) =
    HorizontalDivider(modifier.padding(horizontal = inset), thickness = 1.dp, color = colors.rule)

@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = colors.inkMuted) =
    Text(text.uppercase(), style = TypeRole.kicker, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = Space.gutter, end = Space.s, top = Space.xxl, bottom = Space.s).semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Kicker(text, Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * Top-of-screen title block. It scrolls away with content; [CompactBar] takes over once it has.
 */
@Composable
fun Masthead(kicker: String, title: String, status: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = Space.gutter, end = Space.s, top = Space.s, bottom = Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = Space.touch)) {
            Kicker(kicker, Modifier.weight(1f))
            actions()
        }
        Text(title, style = TypeRole.display, color = colors.ink, modifier = Modifier.semantics { heading() })
        status?.let { Spacer(Modifier.height(Space.xs)); Text(it, style = TypeRole.meta, color = colors.inkMuted) }
    }
}

/** Slim bar that fades in once the masthead has scrolled away (or always, on detail pages). */
@Composable
fun CompactBar(title: String, visible: Boolean, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Surface(color = colors.paper.copy(alpha = if (visible) 0.97f else 0f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.ink) }
                else Spacer(Modifier.width(Space.l))
                AnimatedVisibility(visible, Modifier.weight(1f), enter = fadeIn(tween(Motion.QUICK)), exit = fadeOut(tween(Motion.QUICK))) {
                    Text(title, style = TypeRole.title, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!visible) Spacer(Modifier.weight(1f))
                actions()
            }
            if (visible) HorizontalDivider(thickness = 1.dp, color = colors.rule)
        }
    }
}

// ---------- Stories ----------

/** The central component: one item in any list. Tap to open, long-press for actions, bookmark to save. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StoryRow(
    article: Article, saved: Boolean, unread: Boolean, why: String?,
    onOpen: () -> Unit, onToggleSave: () -> Unit, onActions: () -> Unit,
    showDek: Boolean = true, rank: Int? = null,
) {
    val haptics = LocalHapticFeedback.current
    val label = buildString {
        if (unread) append("Unread. ")
        append(article.title); append(". "); append(article.sourceLabel()); append(", "); append(timeAgo(article.published))
        append(", ${article.minutes} minute read."); why?.let { append(" $it") }
    }
    Column(
        Modifier.fillMaxWidth()
            .combinedClickable(
                onClickLabel = "Open", onLongClickLabel = "More actions", role = Role.Button,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onActions() }, onClick = onOpen,
            ),
    ) {
        Row(Modifier.padding(start = Space.gutter, end = Space.xs, top = Space.l, bottom = Space.m), verticalAlignment = Alignment.Top) {
            if (rank != null) {
                Text("$rank", style = TypeRole.headline, color = colors.inkFaint, modifier = Modifier.width(36.dp))
            }
            // Screen readers hear one sentence for the story; the bookmark stays a separate button.
            Column(Modifier.weight(1f).padding(end = Space.s).clearAndSetSemantics { contentDescription = label }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (unread) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(colors.saffron))
                        Spacer(Modifier.width(6.dp))
                    }
                    Kicker("${article.sourceLabel()} · ${timeAgo(article.published)}")
                }
                Spacer(Modifier.height(6.dp))
                Text(article.title, style = TypeRole.story, color = colors.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (showDek && article.excerpt.isNotBlank()) {
                    Spacer(Modifier.height(Space.xs))
                    Text(article.excerpt, style = TypeRole.ui, color = colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(Space.s))
                Text(listOfNotNull("${article.minutes} min", why).joinToString("  ·  "), style = TypeRole.meta, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SaveToggle(saved, onToggleSave)
        }
        Hairline()
    }
}

@Composable
fun SaveToggle(saved: Boolean, onToggle: () -> Unit) {
    val scale by animateFloatAsState(if (saved) 1f else 0.92f, tween(Motion.QUICK), label = "save")
    val tint by animateColorAsState(if (saved) colors.evergreen else colors.inkMuted, tween(Motion.QUICK), label = "saveTint")
    IconButton(onClick = onToggle) {
        Icon(if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, contentDescription = if (saved) "Remove from Library" else "Save to Library",
            tint = tint, modifier = Modifier.scale(scale))
    }
}

@Composable
fun StorySkeleton(rows: Int = 4) {
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton").animateFloat(
        0.45f, 0.9f, androidx.compose.animation.core.infiniteRepeatable(tween(1200), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse",
    )
    Column(Modifier.alpha(pulse).semantics { contentDescription = "Loading" }) {
        repeat(rows) {
            Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Bar(0.35f, 10.dp); Bar(0.95f, 16.dp); Bar(0.7f, 16.dp); Bar(0.25f, 10.dp)
            }
            Hairline()
        }
    }
}

@Composable
private fun Bar(width: Float, height: Dp) =
    Box(Modifier.fillMaxWidth(width).height(height).clip(RoundedCornerShape(4.dp)).background(colors.paperSunk))

// ---------- Choices and actions ----------

data class SheetAction(val label: String, val icon: ImageVector, val description: String? = null, val destructive: Boolean = false, val selected: Boolean = false, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String, actions: List<SheetAction>, onDismiss: () -> Unit, subtitle: String? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.paperRaised) {
        Column(Modifier.navigationBarsPadding().padding(bottom = Space.l)) {
            Text(title, style = TypeRole.title, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Space.xxl).semantics { heading() })
            subtitle?.let { Text(it, style = TypeRole.meta, color = colors.inkMuted, modifier = Modifier.padding(horizontal = Space.xxl, vertical = Space.xs)) }
            Spacer(Modifier.height(Space.s))
            actions.forEach { a ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onDismiss(); a.onClick() }.padding(horizontal = Space.xxl, vertical = Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(a.icon, null, tint = when { a.destructive -> colors.danger; a.selected -> colors.evergreen; else -> colors.inkMuted })
                    Spacer(Modifier.width(Space.l))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, style = TypeRole.ui, color = if (a.destructive) colors.danger else colors.ink)
                        a.description?.let { Text(it, style = TypeRole.meta, color = colors.inkMuted) }
                    }
                    if (a.selected) Kicker("Current", color = colors.evergreen)
                }
            }
        }
    }
}

/** Underlined text tabs, for switching views of the same content. */
@Composable
fun TextTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = Space.gutter - Space.s), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            tabs.forEachIndexed { i, label ->
                val active = i == selected
                val color by animateColorAsState(if (active) colors.ink else colors.inkMuted, tween(Motion.QUICK), label = "tab")
                Column(
                    Modifier.clip(RoundedCornerShape(Radius.control / 2)).clickable(role = Role.Tab) { onSelect(i) }.padding(horizontal = Space.s, vertical = Space.m),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(label, style = TypeRole.title, color = color)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(24.dp).height(2.dp).background(if (active) colors.evergreen else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = colors.rule)
    }
}

@Composable
fun Pill(label: String, selected: Boolean, onClick: () -> Unit, leading: ImageVector? = null) =
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label, style = TypeRole.meta) }, shape = CircleShape,
        leadingIcon = leading?.let { { Icon(it, null, Modifier.size(18.dp)) } },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.paper, labelColor = colors.ink, iconColor = colors.inkMuted,
            selectedContainerColor = colors.evergreenSoft, selectedLabelColor = colors.evergreen, selectedLeadingIconColor = colors.evergreen,
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = colors.rule, selectedBorderColor = colors.evergreenSoft),
    )

/** Layer shown by shape as well as color, so it reads without color vision. */
@Composable
fun LayerMark(layer: Layer, size: Dp = 10.dp) {
    val c = colors
    Box(Modifier.size(size).drawBehind {
        val r = this.size.minDimension / 2
        val center = Offset(this.size.width / 2, this.size.height / 2)
        when (layer) {
            Layer.Core -> drawCircle(c.evergreen, r, center)
            Layer.Near -> { drawCircle(c.evergreen, r, center, style = Stroke(1.5.dp.toPx())); drawArc(c.evergreen, 90f, 180f, true, size = this.size) }
            Layer.Adjacent -> drawCircle(c.inkMuted, r - 0.75.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
            Layer.Discovery -> drawCircle(c.inkMuted, r - 0.75.dp.toPx(), center, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))))
            Layer.Muted -> { drawCircle(c.inkFaint, r - 0.75.dp.toPx(), center, style = Stroke(1.5.dp.toPx())); drawLine(c.inkFaint, Offset(0f, this.size.height), Offset(this.size.width, 0f), 1.5.dp.toPx()) }
        }
    })
}

@Composable
fun LayerLabel(layer: Layer) = Row(verticalAlignment = Alignment.CenterVertically) {
    LayerMark(layer); Spacer(Modifier.width(6.dp)); Text(layer.name, style = TypeRole.meta, color = colors.inkMuted)
}

// ---------- Buttons ----------

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) =
    Button(onClick, modifier.heightIn(min = Space.touch), enabled = enabled, shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = colors.evergreen, contentColor = colors.onEvergreen),
        contentPadding = PaddingValues(horizontal = Space.xxl)) {
        icon?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(Space.s)) }
        Text(text, style = TypeRole.title)
    }

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) =
    OutlinedButton(onClick, modifier.heightIn(min = Space.touch), enabled = enabled, shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.rule), contentPadding = PaddingValues(horizontal = Space.xl)) {
        icon?.let { Icon(it, null, Modifier.size(18.dp), tint = colors.evergreen); Spacer(Modifier.width(Space.s)) }
        Text(text, style = TypeRole.title, color = colors.ink)
    }

// ---------- States ----------

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.xxxl, vertical = Space.huge),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(colors.paperSunk), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(28.dp), tint = colors.inkMuted)
        }
        Text(title, style = TypeRole.headline, color = colors.ink, textAlign = TextAlign.Center)
        Text(body, style = TypeRole.ui, color = colors.inkMuted, textAlign = TextAlign.Center)
        action?.let { Spacer(Modifier.height(Space.xs)); it() }
    }
}

/** Inline, persistent notice about the state of the screen (offline, failing sources). Not a snackbar. */
@Composable
fun Banner(icon: ImageVector, text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s).clip(RoundedCornerShape(Radius.control))
            .background(colors.paperSunk).padding(start = Space.l, end = Space.xs, top = Space.xs, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.inkMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.m))
        Text(text, style = TypeRole.meta, color = colors.ink, modifier = Modifier.weight(1f).padding(vertical = Space.s))
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel, style = TypeRole.title, color = colors.evergreen) }
    }
}

// ---------- Settings ----------

@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Space.l).clip(RoundedCornerShape(Radius.control)).background(colors.paperSunk).padding(vertical = Space.xs),
        content = content,
    )
}

@Composable
fun SettingRow(
    title: String, icon: ImageVector? = null, supporting: String? = null, onClick: (() -> Unit)? = null,
    destructive: Boolean = false, trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = if (destructive) colors.danger else colors.inkMuted); Spacer(Modifier.width(Space.l)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = TypeRole.ui, color = if (destructive) colors.danger else colors.ink)
            supporting?.let { Text(it, style = TypeRole.meta.copy(fontWeight = TypeRole.ui.fontWeight), color = colors.inkMuted) }
        }
        trailing?.let { Spacer(Modifier.width(Space.s)); it() }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, icon: ImageVector? = null, supporting: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    SettingRow(title, icon, supporting, onClick = if (enabled) ({ onChange(!checked) }) else null) {
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, destructive: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) =
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = colors.paperRaised,
        title = { Text(title, style = TypeRole.headline, color = colors.ink) }, text = { Text(body, style = TypeRole.ui, color = colors.inkMuted) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, style = TypeRole.title, color = if (destructive) colors.danger else colors.evergreen) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = TypeRole.title, color = colors.inkMuted) } },
    )


// ---------- Shared names used by settings pages ----------

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) = SectionLabel(text, modifier)

@Composable
fun MenuRow(text: String, icon: ImageVector, onClick: () -> Unit) =
    androidx.compose.material3.DropdownMenuItem(text = { Text(text, style = TypeRole.ui) }, leadingIcon = { Icon(icon, null, tint = colors.inkMuted) }, onClick = onClick)
