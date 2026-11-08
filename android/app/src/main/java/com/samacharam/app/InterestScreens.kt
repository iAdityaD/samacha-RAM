package com.samacharam.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.samacharam.app.ui.ActionSheet
import com.samacharam.app.ui.ConfirmDialog
import com.samacharam.app.ui.EmptyState
import com.samacharam.app.ui.Hairline
import com.samacharam.app.ui.LayerMark
import com.samacharam.app.ui.Pill
import com.samacharam.app.ui.PrimaryButton
import com.samacharam.app.ui.Radius
import com.samacharam.app.ui.SectionLabel
import com.samacharam.app.ui.SettingRow
import com.samacharam.app.ui.SettingsGroup
import com.samacharam.app.ui.SheetAction
import com.samacharam.app.ui.Space
import com.samacharam.app.ui.TypeRole
import com.samacharam.app.ui.colors
import com.samacharam.app.ui.timeAgo
import com.samacharam.core.ContentType
import com.samacharam.core.DAY_MS
import com.samacharam.core.Layer
import com.samacharam.core.app.Feeds
import com.samacharam.core.db.SourceState
import com.samacharam.core.formatDate

val LAYER_HELP = mapOf(
    Layer.Core to "Leads your feed and briefing", Layer.Near to "Closely related, shown next",
    Layer.Adjacent to "Neighboring areas, shown less often", Layer.Discovery to "Occasional exploration",
    Layer.Muted to "Never shown",
)

@Composable
fun formFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = colors.paperRaised, unfocusedContainerColor = colors.paperSunk,
    unfocusedBorderColor = colors.paperSunk, focusedBorderColor = colors.evergreen,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddTopicDialog(ctx: AppContext, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var layer by remember { mutableStateOf(Layer.Near) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = colors.paperRaised,
        title = { Text("Add a topic", style = TypeRole.headline, color = colors.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                OutlinedTextField(name, { name = it }, label = { Text("Topic, e.g. Robotics") }, singleLine = true, textStyle = TypeRole.ui,
                    shape = RoundedCornerShape(Radius.control), colors = formFieldColors(), modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    listOf(Layer.Core, Layer.Near, Layer.Adjacent, Layer.Discovery).forEach { l -> Pill(l.name, layer == l, { layer = l }) }
                }
                Text("Items are tagged when the topic appears in a title or excerpt. A classification model can tag more precisely.",
                    style = TypeRole.meta.copy(fontWeight = TypeRole.ui.fontWeight), color = colors.inkMuted)
            }
        },
        confirmButton = {
            TextButton(onClick = { val n = name.trim(); val l = layer; onDismiss(); ctx.quick { ctx.library.setLayer(n, l); "Added $n to ${l.name}" } }, enabled = name.isNotBlank()) {
                Text("Add", style = TypeRole.title, color = if (name.isNotBlank()) colors.evergreen else colors.inkFaint)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = TypeRole.title, color = colors.inkMuted) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MissionsScreen(ctx: AppContext, nav: NavHostController) {
    val missions by rememberLoaded(ctx) { ctx.library.missions() }
    val topics by rememberLoaded(ctx) { ctx.library.profile().topics.filterValues { it != Layer.Muted }.keys.sorted() }
    var title by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var days by remember { mutableFloatStateOf(7f) }
    AppPage(ctx, "Missions", nav = nav) { padding ->
        PageColumn(padding) {
            Text("A mission is a temporary focus, like “local speech models this week”. It gathers matching stories into a deck on your Profile until its end date and never changes your layers.",
                style = TypeRole.ui, color = colors.inkMuted, modifier = Modifier.padding(horizontal = Space.gutter))
            if (!missions.isNullOrEmpty()) {
                SectionLabel("Your missions")
                SettingsGroup {
                    missions.orEmpty().forEach { m ->
                        val ended = m.expires < formatDate(System.currentTimeMillis())
                        SettingRow(m.title, Icons.Outlined.Flag, "${m.topics.joinToString()} · ${if (ended) "ended" else "until"} ${m.expires}") {
                            IconButton(onClick = { ctx.quick { ctx.library.deleteMission(m.id.toLong()); "Mission removed" } }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete ${m.title}", tint = colors.inkMuted)
                            }
                        }
                    }
                }
            }
            SectionLabel("New mission")
            Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true, textStyle = TypeRole.ui,
                    shape = RoundedCornerShape(Radius.control), colors = formFieldColors(), modifier = Modifier.fillMaxWidth())
                Text("Topics", style = TypeRole.title, color = colors.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    topics.orEmpty().forEach { t -> Pill(t, t in chosen, { chosen = if (t in chosen) chosen - t else chosen + t }) }
                }
                Text("Ends in ${days.toInt()} day${if (days.toInt() == 1) "" else "s"}", style = TypeRole.title, color = colors.ink)
                Slider(days, { days = it }, valueRange = 1f..30f,
                    colors = SliderDefaults.colors(thumbColor = colors.evergreen, activeTrackColor = colors.evergreen, inactiveTrackColor = colors.rule))
                PrimaryButton("Start mission", {
                    val n = title; val t = chosen; val until = formatDate(System.currentTimeMillis() + days.toLong() * DAY_MS)
                    ctx.quick { ctx.library.addMission(n, t, until, System.currentTimeMillis()); title = ""; chosen = emptySet(); "Mission started. Find it on your Profile." }
                }, Modifier.fillMaxWidth(), enabled = title.isNotBlank() && chosen.isNotEmpty())
                if (title.isNotBlank() && chosen.isEmpty()) Text("Choose at least one topic.", style = TypeRole.meta, color = colors.inkMuted)
            }
        }
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
    val urlError = url.isNotEmpty() && !url.startsWith("https://")
    AppPage(ctx, "Add a feed", nav = nav) { padding ->
        PageColumn(padding) {
            Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                OutlinedTextField(url, { url = it.trim() }, label = { Text("Feed URL") }, placeholder = { Text("https://example.com/feed.xml") },
                    singleLine = true, textStyle = TypeRole.ui, isError = urlError, shape = RoundedCornerShape(Radius.control), colors = formFieldColors(),
                    supportingText = { Text(if (urlError) "Feeds must use https://" else "RSS, Atom, or JSON Feed") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, textStyle = TypeRole.ui,
                    shape = RoundedCornerShape(Radius.control), colors = formFieldColors(), modifier = Modifier.fillMaxWidth())
                Text("Kind of content", style = TypeRole.title, color = colors.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    listOf(ContentType.Blog, ContentType.News, ContentType.Paper).forEach { t -> Pill(t.name, type == t, { type = t }) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("Default topic", style = TypeRole.title, color = colors.ink)
                    Text("Used when an item matches none of your topics.", style = TypeRole.meta, color = colors.inkMuted)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    topics.orEmpty().forEach { t -> Pill(t, topic == t, { topic = t }) }
                }
                PrimaryButton("Add and fetch", {
                    val u = url; val n = name.trim(); val ty = type; val tp = topic
                    ctx.run {
                        val key = ctx.library.addSource(u, n, ty, tp)
                        val result = PhoneLibrary.refresher(ctx.activity).refresh().sources.firstOrNull { it.key == key }
                        ctx.activity.runOnUiThread { nav.popBackStack() }
                        if (result?.error != null) "Added $n, but the first fetch failed: ${result.error}" else "Added $n · ${result?.newItems ?: 0} new items"
                    }
                }, Modifier.fillMaxWidth(), enabled = url.startsWith("https://") && name.isNotBlank() && topic.isNotBlank() && !ctx.busy)
                Text("samacha·RAM stores feed excerpts and links to the publisher. It never copies full articles.", style = TypeRole.meta, color = colors.inkMuted)
            }
        }
    }
}
