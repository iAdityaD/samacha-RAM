package com.samacharam.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samacharam.core.Article
import com.samacharam.core.ContentType
import com.samacharam.core.Layer

fun timeAgo(epochMs: Long?): String =
    epochMs?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString() } ?: "Date unknown"

fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').removePrefix("www.")

/** Stable, readable color per source so items from one publisher are recognizable at a glance. */
@Composable
fun sourceColor(key: String): Color {
    val palette = listOf(
        MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary,
        Color(0xFF5B6CC0), Color(0xFFB0476A), Color(0xFF2E8A9A),
    )
    return palette[(key.hashCode() and 0x7fffffff) % palette.size]
}

@Composable
fun SourceAvatar(name: String, key: String, size: Int = 36) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(sourceColor(key).copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().take(1).uppercase().ifEmpty { "•" }, color = sourceColor(key), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
fun Pill(text: String, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraSmall) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
fun TypePill(type: ContentType) = when (type) {
    ContentType.Paper -> Pill("PAPER", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
    ContentType.News -> Pill("NEWS")
    ContentType.Blog -> Pill("BLOG")
    ContentType.Article -> Pill("LINK")
}

@Composable
fun LayerPill(layer: Layer) = when (layer) {
    Layer.Core -> Pill("CORE", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
    Layer.Near -> Pill("NEAR")
    Layer.Adjacent -> Pill("ADJACENT", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
    Layer.Discovery -> Pill("DISCOVERY", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
    Layer.Muted -> Pill("MUTED", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
}

/** Actions offered from an item's overflow menu. */
data class ArticleActions(
    val onOpen: () -> Unit,
    val onToggleSave: () -> Unit,
    val onMarkRead: () -> Unit,
    val onMore: () -> Unit,
    val onLess: () -> Unit,
    val onShare: () -> Unit,
    val onOriginal: () -> Unit,
)

@Composable
fun ArticleCard(article: Article, saved: Boolean, reason: String?, layer: Layer?, actions: ArticleActions, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(onClick = actions.onOpen, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceAvatar(article.sourceName.ifBlank { hostOf(article.url) }, article.sourceKey.ifBlank { article.url }, 28)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(article.sourceName.ifBlank { hostOf(article.url) }, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${timeAgo(article.published)} · ${article.minutes} min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = actions.onToggleSave) {
                    Icon(if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, contentDescription = if (saved) "Unsave" else "Save",
                        tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More actions") }
                    DropdownMenu(menu, { menu = false }) {
                        MenuRow("Mark as read", Icons.Outlined.DoneAll) { menu = false; actions.onMarkRead() }
                        MenuRow("More like this", Icons.Outlined.ThumbUp) { menu = false; actions.onMore() }
                        MenuRow("Less like this", Icons.Outlined.ThumbDown) { menu = false; actions.onLess() }
                        HorizontalDivider()
                        MenuRow("Share link", Icons.Outlined.Share) { menu = false; actions.onShare() }
                        MenuRow("Open original", Icons.AutoMirrored.Outlined.OpenInNew) { menu = false; actions.onOriginal() }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(article.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 12.dp))
            if (article.excerpt.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(article.excerpt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 12.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TypePill(article.type)
                layer?.let { LayerPill(it) }
                reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
fun MenuRow(text: String, icon: ImageVector, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp))

/** A settings row: icon, title, description, optional trailing content, tappable. */
@Composable
fun SettingRow(title: String, icon: ImageVector? = null, supporting: String? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
fun SwitchRow(title: String, checked: Boolean, icon: ImageVector? = null, supporting: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    SettingRow(title, icon, supporting, onClick = if (enabled) ({ onChange(!checked) }) else null) { Switch(checked, onChange, enabled = enabled) }

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, destructive: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) =
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) }, text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

/** A rounded, tonal group for settings rows. */
@Composable
fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) { Column(Modifier.padding(vertical = 4.dp)) { content() } }
}
