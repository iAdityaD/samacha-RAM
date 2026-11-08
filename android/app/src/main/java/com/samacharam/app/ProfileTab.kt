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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.samacharam.app.ui.ActionSheet
import com.samacharam.app.ui.Avatar
import com.samacharam.app.ui.ConfirmDialog
import com.samacharam.app.ui.Count
import com.samacharam.app.ui.EmptyState
import com.samacharam.app.ui.Hairline
import com.samacharam.app.ui.IconTabs
import com.samacharam.app.ui.LayerMark
import com.samacharam.app.ui.Radius
import com.samacharam.app.ui.SectionLabel
import com.samacharam.app.ui.SheetAction
import com.samacharam.app.ui.SmallButton
import com.samacharam.app.ui.Space
import com.samacharam.app.ui.StoryCircle
import com.samacharam.app.ui.TabBar
import com.samacharam.app.ui.TypeRole
import com.samacharam.app.ui.colors
import com.samacharam.app.ui.timeAgo
import com.samacharam.core.Layer
import com.samacharam.core.Mission
import com.samacharam.core.app.Feeds
import com.samacharam.core.db.SourceState

private data class ProfileData(
    val name: String, val read: Int, val saved: Int, val topics: List<Triple<String, Layer, Int>>, val budget: Int,
    val missions: List<Mission>, val sources: List<SourceState>, val streak: Int, val briefingTime: String, val briefingOn: Boolean,
)

/** Profile: you as a reader. Your counts, missions, topics and sources. Settings sit behind the menu. */
@Composable
fun ProfileScreen(ctx: AppContext, nav: NavHostController) {
    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var acting by remember { mutableStateOf<SourceState?>(null) }
    var removing by remember { mutableStateOf<SourceState?>(null) }
    val data by rememberLoaded(ctx) {
        val l = ctx.library
        val counts = l.articles().flatMap { it.topics }.groupingBy { it }.eachCount()
        val profile = l.profile()
        ProfileData(
            l.meta("ui.name")?.ifBlank { null } ?: "Reader", l.seenIds().size, l.savedIds().size,
            profile.topics.entries.sortedWith(compareByDescending<Map.Entry<String, Layer>> { it.value.weight }.thenBy { it.key }).map { Triple(it.key, it.value, counts[it.key] ?: 0) },
            profile.budgetMinutes, Feeds.activeMissions(l, System.currentTimeMillis()), l.sources(), readingStreak(l), l.briefingTime, l.briefingEnabled,
        )
    }
    val d = data

    Column(Modifier.fillMaxSize().background(colors.paper)) {
        TabBar(d?.name ?: "") {
            IconButton(onClick = { if (ctx.profileTab == 0) adding = true else nav.navigate(Routes.ADD_SOURCE) }) {
                Icon(Icons.Outlined.AddBox, contentDescription = if (ctx.profileTab == 0) "Add topic" else "Add feed", tint = colors.ink)
            }
            IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Outlined.Menu, contentDescription = "Settings and data", tint = colors.ink) }
        }
        BusyLine(ctx)
        if (d == null) return@Column
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = Space.xxl)) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.border(2.dp, colors.evergreen, CircleShape).padding(4.dp)) { Avatar(d.name, 76.dp, filled = true) }
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Count("${d.read}", "read"); Count("${d.saved}", "saved"); Count("${d.topics.count { it.second != Layer.Muted }}", "topics")
                        }
                    }
                    Spacer(Modifier.height(Space.m))
                    Text(d.name, style = TypeRole.title, color = colors.ink)
                    val core = d.topics.filter { it.second == Layer.Core }.take(3).joinToString(" · ") { it.first }
                    Text(
                        listOfNotNull(core.ifBlank { null },
                            listOfNotNull(if (d.streak > 0) "${d.streak}-day streak" else null, if (d.briefingOn) "briefing at ${d.briefingTime}" else null).joinToString(" · ").ifBlank { null },
                        ).joinToString("\n"),
                        style = TypeRole.ui, color = colors.inkMuted,
                    )
                    Spacer(Modifier.height(Space.m))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        SmallButton("Edit name", { renaming = true }, Modifier.weight(1f))
                        SmallButton("Share briefing", {
                            ctx.run {
                                val q = Feeds.dailyBriefing(ctx.library, System.currentTimeMillis()).first
                                if (q.articles.isEmpty()) return@run "Your briefing is empty right now."
                                val text = "My briefing (${q.minutes} min)\n\n" + q.articles.joinToString("\n\n") { "• ${it.title}\n  ${it.url}" }
                                ctx.activity.runOnUiThread { ctx.shareText("My samacha·RAM briefing", text) }
                                null
                            }
                        }, Modifier.weight(1f))
                    }
                }
            }
            item(key = "missions") {
                LazyRow(contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    items(d.missions, key = { it.id }) { m ->
                        StoryCircle(m.title, false, { nav.navigate(Routes.story(Decks.mission(m.id))) }, size = 56.dp) { Avatar(m.title, 56.dp, icon = Icons.Outlined.Flag) }
                    }
                    item(key = "new") {
                        StoryCircle(if (d.missions.isEmpty()) "Missions" else "New", false, { nav.navigate(Routes.MISSIONS) }, size = 56.dp, dashed = true) {
                            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, null, tint = colors.ink) }
                        }
                    }
                }
            }
            item(key = "tabs") { IconTabs(listOf(Icons.Outlined.Tag to "Topics", Icons.Outlined.RssFeed to "Sources"), ctx.profileTab, { ctx.profileTab = it }) }
            if (ctx.profileTab == 0) {
                item(key = "legend") {
                    Row(Modifier.padding(horizontal = Space.gutter, vertical = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                        listOf(Layer.Core, Layer.Near, Layer.Adjacent, Layer.Discovery).forEach { l ->
                            Row(verticalAlignment = Alignment.CenterVertically) { LayerMark(l, 10.dp); Spacer(Modifier.width(Space.xs)); Text(l.name, style = TypeRole.meta, color = colors.inkMuted) }
                        }
                    }
                }
                if (d.topics.isEmpty()) item { EmptyState(Icons.Outlined.Tag, "No topics yet", "Add the subjects you follow. Stories are ranked by them.") }
                items(d.topics, key = { "t-${it.first}" }) { (t, l, n) ->
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open topic") { nav.navigate(Routes.topic(t)) }.semantics { stateDescription = l.name }
                            .padding(horizontal = Space.gutter, vertical = Space.m),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LayerMark(l, 12.dp); Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(t, style = TypeRole.ui.copy(fontSize = 16.sp), color = if (l == Layer.Muted) colors.inkMuted else colors.ink)
                            Text("${l.name} · $n stor${if (n == 1) "y" else "ies"}", style = TypeRole.meta, color = colors.inkMuted)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = colors.inkFaint)
                    }
                    Hairline()
                }
                item(key = "budget") {
                    var budget by remember(d.budget) { mutableFloatStateOf(d.budget.toFloat()) }
                    SectionLabel("Daily briefing length", trailing = { Text("${budget.toInt()} min", style = TypeRole.meta, color = colors.ink, modifier = Modifier.padding(end = Space.m)) })
                    Slider(budget, { budget = it }, valueRange = 5f..60f, steps = 10, modifier = Modifier.padding(horizontal = Space.gutter),
                        colors = SliderDefaults.colors(thumbColor = colors.evergreen, activeTrackColor = colors.evergreen, inactiveTrackColor = colors.rule),
                        onValueChangeFinished = { val b = budget.toInt(); ctx.quick { ctx.library.budgetMinutes = b; null } })
                    Text("The briefing ends when this time is used.", style = TypeRole.meta, color = colors.inkMuted, modifier = Modifier.padding(horizontal = Space.gutter))
                }
            } else {
                val active = d.sources.count { it.enabled }
                val healthy = d.sources.count { it.enabled && it.lastError == null && it.lastSuccess != null }
                item(key = "src-head") {
                    Row(Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
                        Text("$healthy of $active active sources healthy", style = TypeRole.meta, color = colors.inkMuted, modifier = Modifier.weight(1f))
                        SmallButton("Add feed", { nav.navigate(Routes.ADD_SOURCE) }, primary = true)
                    }
                }
                if (d.sources.isEmpty()) item { EmptyState(Icons.Outlined.RssFeed, "No sources yet", "Add an RSS, Atom, or JSON feed to start gathering stories.") }
                items(d.sources, key = { "s-${it.key}" }) { s -> SourceRow(ctx, s) { acting = s } }
            }
        }
    }

    if (renaming) NameDialog(d?.name ?: "", { renaming = false }) { n -> ctx.quick { ctx.library.setMeta("ui.name", n.trim()); null } }
    if (adding) AddTopicDialog(ctx) { adding = false }
    acting?.let { s ->
        ActionSheet(s.name, listOf(
            SheetAction(if (s.enabled) "Pause" else "Resume", Icons.Outlined.PauseCircle) { ctx.quick { ctx.library.setSourceEnabled(s.key, !s.enabled); null } },
            SheetAction("Unsubscribe", Icons.Outlined.Delete, "Stored items stay until cleanup", destructive = true) { removing = s },
        ), { acting = null }, subtitle = s.url)
    }
    removing?.let { s ->
        ConfirmDialog("Unsubscribe from ${s.name}?", "Items already stored stay until cleanup. You can add the feed again later.", "Unsubscribe", destructive = true,
            onDismiss = { removing = null }) { ctx.quick { ctx.library.removeSource(s.key); "Unsubscribed from ${s.name}" } }
    }
}

@Composable
private fun SourceRow(ctx: AppContext, s: SourceState, onActions: () -> Unit) {
    val failing = s.enabled && s.lastError != null
    val (icon, status) = when {
        !s.enabled -> Icons.Outlined.PauseCircle to "Paused"
        s.lastError != null -> Icons.Outlined.ErrorOutline to s.lastError!!
        s.lastSuccess != null -> Icons.Outlined.CheckCircle to "${s.itemCount} items · checked ${timeAgo(s.lastChecked ?: s.lastSuccess)}"
        else -> Icons.Outlined.Schedule to "Not checked yet"
    }
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Source options") { onActions() }.padding(start = Space.gutter, end = Space.m, top = Space.m, bottom = Space.m),
        verticalAlignment = Alignment.CenterVertically) {
        Avatar(s.name, 40.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(s.name, style = TypeRole.title.copy(fontSize = 15.sp), color = if (s.enabled) colors.ink else colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(13.dp), tint = when { failing -> colors.danger; s.enabled && s.lastSuccess != null -> colors.evergreen; else -> colors.inkMuted })
                Spacer(Modifier.width(Space.xs))
                Text("${s.type.name} · $status", style = TypeRole.meta, color = if (failing) colors.danger else colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Switch(s.enabled, { on -> ctx.quick { ctx.library.setSourceEnabled(s.key, on); null } },
            colors = SwitchDefaults.colors(checkedTrackColor = colors.evergreen, checkedThumbColor = colors.onEvergreen))
    }
    Hairline()
}

@Composable
private fun NameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(if (current == "Reader") "" else current) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = colors.paperRaised,
        title = { Text("Your name", style = TypeRole.headline, color = colors.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedTextField(name, { name = it.take(40) }, singleLine = true, textStyle = TypeRole.ui, placeholder = { Text("Reader") },
                    shape = RoundedCornerShape(Radius.control), colors = formFieldColors(), modifier = Modifier.fillMaxWidth())
                Text("Shown only on this phone. It is never sent anywhere.", style = TypeRole.meta, color = colors.inkMuted)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name); onDismiss() }) { Text("Save", style = TypeRole.title, color = colors.evergreen) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = TypeRole.title, color = colors.inkMuted) } },
    )
}

