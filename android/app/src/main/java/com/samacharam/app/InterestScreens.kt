package com.samacharam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.RadioButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.samacharam.app.ui.ConfirmDialog
import com.samacharam.app.ui.EmptyState
import com.samacharam.app.ui.MenuRow
import com.samacharam.app.ui.SectionHeader
import com.samacharam.app.ui.SettingRow
import com.samacharam.app.ui.SettingsGroup
import com.samacharam.app.ui.SourceAvatar
import com.samacharam.app.ui.timeAgo
import com.samacharam.core.ContentType
import com.samacharam.core.DAY_MS
import com.samacharam.core.Layer
import com.samacharam.core.formatDate

private val LAYER_HELP = mapOf(
    Layer.Core to "Leads your feed", Layer.Near to "Closely related", Layer.Adjacent to "Neighboring areas",
    Layer.Discovery to "Occasional exploration", Layer.Muted to "Never shown",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InterestsScreen(ctx: AppContext, nav: NavHostController) {
    val profile by rememberLoaded(ctx) { ctx.library.profile() }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }

    AppPage(ctx, "Interests", floating = {
        ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add topic") })
    }) { padding ->
        val p = profile ?: return@AppPage
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Text("Arrange topics in layers. Core items lead your feed; Muted never appear. Feedback only reorders items inside a layer.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            }
            item {
                SectionHeader("Reading")
                SettingsGroup {
                    var budget by remember(p.budgetMinutes) { mutableFloatStateOf(p.budgetMinutes.toFloat()) }
                    SettingRow("Daily reading budget", Icons.Outlined.Timer, "${budget.toInt()} minutes — your briefing stops when it is used up")
                    Slider(budget, { budget = it }, valueRange = 5f..60f, steps = 10, modifier = Modifier.padding(horizontal = 24.dp),
                        onValueChangeFinished = { val b = budget.toInt(); ctx.quick { ctx.library.budgetMinutes = b; null } })
                    SettingRow("Missions", Icons.Outlined.Flag, "Temporary focus areas with an end date", onClick = { nav.navigate(Routes.MISSIONS) })
                }
            }
            Layer.entries.forEach { layer ->
                val topics = p.topics.filterValues { it == layer }.keys.sorted()
                if (topics.isEmpty()) return@forEach
                item(key = "h-$layer") { SectionHeader("${layer.name} · ${LAYER_HELP[layer]}") }
                items(topics, key = { it }) { topic ->
                    var menu by remember { mutableStateOf(false) }
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(topic, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Box {
                                AssistChip(onClick = { menu = true }, label = { Text(layer.name) }, trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Change layer for $topic") })
                                DropdownMenu(menu, { menu = false }) {
                                    Layer.entries.forEach { l ->
                                        DropdownMenuItem(
                                            text = { Column { Text(l.name); Text(LAYER_HELP.getValue(l), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                                            leadingIcon = { RadioButton(selected = layer == l, onClick = null) },
                                            onClick = { menu = false; ctx.quick { ctx.library.setLayer(topic, l); null } },
                                        )
                                    }
                                    HorizontalDivider()
                                    MenuRow("Remove topic", Icons.Outlined.Delete) { menu = false; removing = topic }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var layer by remember { mutableStateOf(Layer.Near) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add a topic") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Topic name, e.g. Robotics") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Layer", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(Layer.Core, Layer.Near, Layer.Adjacent, Layer.Discovery).forEach { l -> FilterChip(layer == l, { layer = l }, { Text(l.name) }) }
                    }
                    Text("Items are tagged when the topic name appears in their title or excerpt. Connect a classification model for smarter tagging.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { val n = name.trim(); val l = layer; adding = false; ctx.quick { ctx.library.setLayer(n, l); "Added $n" } }, enabled = name.isNotBlank()) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
    removing?.let { topic ->
        ConfirmDialog("Remove $topic?", "It is removed from your interests and from item tags. Saved items stay.", "Remove", destructive = true,
            onDismiss = { removing = null }) { ctx.quick { ctx.library.removeTopic(topic); "Removed $topic" } }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MissionsScreen(ctx: AppContext, nav: NavHostController) {
    val missions by rememberLoaded(ctx) { ctx.library.missions() }
    val topics by rememberLoaded(ctx) { ctx.library.profile().topics.filterValues { it != Layer.Muted }.keys.sorted() }
    var title by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var days by remember { mutableFloatStateOf(7f) }
    AppPage(ctx, "Missions", nav = nav) { padding ->
        PageColumn(padding) {
            Text("A mission is a temporary focus — like “local speech models this week”. It gives Today a filtered feed until its end date and never changes your layers.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            SectionHeader("Active and past")
            if (missions.isNullOrEmpty()) Text("No missions yet.", modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsGroup {
                missions.orEmpty().forEach { m ->
                    val ended = m.expires < formatDate(System.currentTimeMillis())
                    SettingRow(m.title, Icons.Outlined.Flag, "${m.topics.joinToString()} · ${if (ended) "ended" else "until"} ${m.expires}") {
                        IconButton(onClick = { ctx.quick { ctx.library.deleteMission(m.id.toLong()); "Mission removed" } }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete mission") }
                    }
                }
            }
            SectionHeader("New mission")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Topics", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    topics.orEmpty().forEach { t -> FilterChip(t in chosen, { chosen = if (t in chosen) chosen - t else chosen + t }, { Text(t) }) }
                }
                Text("Ends in ${days.toInt()} day${if (days.toInt() == 1) "" else "s"}", style = MaterialTheme.typography.labelLarge)
                Slider(days, { days = it }, valueRange = 1f..30f)
                Button(onClick = {
                    val n = title; val t = chosen; val until = formatDate(System.currentTimeMillis() + days.toLong() * DAY_MS)
                    ctx.quick { ctx.library.addMission(n, t, until, System.currentTimeMillis()); title = ""; chosen = emptySet(); "Mission added — pick it on Today" }
                }, enabled = title.isNotBlank() && chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Start mission") }
            }
        }
    }
}

@Composable
fun SourcesScreen(ctx: AppContext, nav: NavHostController) {
    val sources by rememberLoaded(ctx) { ctx.library.sources() }
    var removing by remember { mutableStateOf<com.samacharam.core.db.SourceState?>(null) }
    AppPage(ctx, "Sources", nav = nav, actions = {
        IconButton(onClick = { ctx.run { PhoneLibrary.summary(PhoneLibrary.refresher(ctx.activity).refresh(force = true)) } }, enabled = !ctx.busy) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh all")
        }
    }, floating = {
        ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.ADD_SOURCE) }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add feed") })
    }) { padding ->
        val list = sources
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (list != null && list.isEmpty()) item { EmptyState(Icons.Outlined.RssFeed, "No sources", "Add an RSS, Atom, or JSON feed to start gathering items.") }
            if (!list.isNullOrEmpty()) item {
                val healthy = list.count { it.enabled && it.lastError == null && it.lastSuccess != null }
                Text("$healthy of ${list.count { it.enabled }} active sources healthy", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            items(list.orEmpty(), key = { it.key }) { s ->
                var menu by remember { mutableStateOf(false) }
                val (statusIcon, status, tint) = when {
                    !s.enabled -> Triple(Icons.Outlined.PauseCircle, "Paused", MaterialTheme.colorScheme.onSurfaceVariant)
                    s.lastError != null -> Triple(Icons.Outlined.ErrorOutline, "${s.lastError} Retrying ${s.nextCheck?.let { "after " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) } ?: "soon"}.", MaterialTheme.colorScheme.error)
                    s.lastSuccess != null -> Triple(Icons.Outlined.CheckCircle, "Healthy · ${s.itemCount} items · checked ${timeAgo(s.lastChecked ?: s.lastSuccess ?: 0L)}", MaterialTheme.colorScheme.primary)
                    else -> Triple(Icons.Outlined.Schedule, "Not checked yet", MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ListItem(
                    leadingContent = { SourceAvatar(s.name, s.key, 40) },
                    headlineContent = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            Text("${s.type.name} · ${s.topic.ifBlank { "no default topic" }}", style = MaterialTheme.typography.bodySmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(statusIcon, null, Modifier.padding(end = 4.dp).width(14.dp), tint = tint)
                                Text(status, style = MaterialTheme.typography.bodySmall, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(s.enabled, { on -> ctx.quick { ctx.library.setSourceEnabled(s.key, on); null } })
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Source actions") }
                                DropdownMenu(menu, { menu = false }) {
                                    MenuRow("Unsubscribe", Icons.Outlined.Delete) { menu = false; removing = s }
                                }
                            }
                        }
                    },
                )
            }
        }
    }
    removing?.let { s ->
        ConfirmDialog("Unsubscribe from ${s.name}?", "Items already stored stay until cleanup. You can add the feed again later.", "Unsubscribe", destructive = true,
            onDismiss = { removing = null }) { ctx.quick { ctx.library.removeSource(s.key); "Unsubscribed from ${s.name}" } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddSourceScreen(ctx: AppContext, nav: NavHostController) {
    val topics by rememberLoaded(ctx) { ctx.library.profile().topics.keys.sorted() }
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ContentType.Blog) }
    var topic by remember { mutableStateOf("") }
    AppPage(ctx, "Add a feed", nav = nav) { padding ->
        PageColumn(padding) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(url, { url = it.trim() }, label = { Text("Feed URL") }, placeholder = { Text("https://example.com/feed.xml") },
                    singleLine = true, leadingIcon = { Icon(Icons.Outlined.RssFeed, null) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Content type", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ContentType.Blog, ContentType.News, ContentType.Paper).forEach { t -> FilterChip(type == t, { type = t }, { Text(t.name) }) }
                }
                Text("Default topic, used when an item matches none of your topics", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    topics.orEmpty().forEach { t -> FilterChip(topic == t, { topic = t }, { Text(t) }) }
                }
                Button(onClick = {
                    val u = url; val n = name.trim(); val ty = type; val tp = topic
                    ctx.run {
                        val key = ctx.library.addSource(u, n, ty, tp)
                        val result = PhoneLibrary.refresher(ctx.activity).refresh().sources.firstOrNull { it.key == key }
                        ctx.activity.runOnUiThread { nav.popBackStack() }
                        if (result?.error != null) "Added $n, but the first fetch failed: ${result.error}" else "Added $n with ${result?.newItems ?: 0} new items"
                    }
                }, enabled = url.startsWith("https://") && name.isNotBlank() && topic.isNotBlank() && !ctx.busy, modifier = Modifier.fillMaxWidth()) { Text("Add and fetch") }
                Text("Public HTTPS feeds only (RSS, Atom, JSON Feed). samacha·RAM stores excerpts and links to the publisher; it never copies full articles.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
