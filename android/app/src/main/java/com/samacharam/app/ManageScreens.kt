package com.samacharam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.samacharam.core.ContentType
import com.samacharam.core.DAY_MS
import com.samacharam.core.Layer
import com.samacharam.core.formatDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InterestsScreen(ctx: AppContext) {
    val profile by rememberLoaded(ctx) { ctx.library.profile() }
    val missions by rememberLoaded(ctx) { ctx.library.missions() }
    var newTopic by remember { mutableStateOf("") }
    ScreenColumn {
        Text("Your interests, in layers.", style = MaterialTheme.typography.headlineMedium)
        Hint("Core items lead your feed, then Near, Adjacent, and Discovery. Muted topics never appear. Your feedback only reorders items inside a layer.")
        val p = profile ?: return@ScreenColumn
        p.topics.entries.sortedWith(compareByDescending<Map.Entry<String, Layer>> { it.value.weight }.thenBy { it.key }).forEach { (topic, layer) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(topic, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { ctx.run { ctx.library.removeTopic(topic); "Removed $topic." } }) { Text("Remove") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Layer.entries.forEach { l ->
                            FilterChip(layer == l, { ctx.run { ctx.library.setLayer(topic, l); null } }, { Text(l.name) })
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newTopic, { newTopic = it }, label = { Text("New topic") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { val t = newTopic; ctx.run { ctx.library.setLayer(t, Layer.Near); newTopic = ""; "Added $t as a Near interest." } }, enabled = newTopic.isNotBlank()) { Text("Add") }
        }
        Hint("New topics are matched by name in titles and excerpts. Connect a classification model for smarter tagging.")

        HorizontalDivider()
        SectionTitle("Daily reading budget")
        var budget by remember(p.budgetMinutes) { mutableFloatStateOf(p.budgetMinutes.toFloat()) }
        Text("${budget.toInt()} minutes")
        Slider(budget, { budget = it }, valueRange = 5f..60f, steps = 10, onValueChangeFinished = { val b = budget.toInt(); ctx.run { ctx.library.budgetMinutes = b; null } })

        HorizontalDivider()
        SectionTitle("Missions")
        Hint("A mission is a temporary focus. It filters a feed until its end date and never changes your layers.")
        missions.orEmpty().forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.title, style = MaterialTheme.typography.titleSmall)
                    Hint("${m.topics.joinToString()} · until ${m.expires}" + if (m.expires < formatDate(System.currentTimeMillis())) " (ended)" else "")
                }
                TextButton(onClick = { ctx.run { ctx.library.deleteMission(m.id.toLong()); "Mission removed." } }) { Text("Delete") }
            }
        }
        var title by remember { mutableStateOf("") }
        var chosen by remember { mutableStateOf(setOf<String>()) }
        var days by remember { mutableFloatStateOf(7f) }
        OutlinedTextField(title, { title = it }, label = { Text("Mission name, e.g. local speech models") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            p.topics.keys.sorted().forEach { t -> FilterChip(t in chosen, { chosen = if (t in chosen) chosen - t else chosen + t }, { Text(t) }) }
        }
        Text("Ends in ${days.toInt()} days")
        Slider(days, { days = it }, valueRange = 1f..30f)
        OutlinedButton(onClick = {
            val name = title; val topics = chosen; val until = formatDate(System.currentTimeMillis() + days.toLong() * DAY_MS)
            ctx.run { ctx.library.addMission(name, topics, until, System.currentTimeMillis()); title = ""; chosen = emptySet(); "Mission added. Pick it on Today." }
        }, enabled = title.isNotBlank() && chosen.isNotEmpty()) { Text("Add mission") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SourcesScreen(ctx: AppContext) {
    val sources by rememberLoaded(ctx) { ctx.library.sources() }
    val topics by rememberLoaded(ctx) { ctx.library.profile().topics.keys.sorted() }
    ScreenColumn {
        Text("Where your news comes from.", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = { ctx.run { PhoneLibrary.summary(PhoneLibrary.refresher(ctx.activity).refresh(force = true)) } }, enabled = !ctx.busy) { Text("Refresh all now") }
        sources.orEmpty().forEach { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Switch(s.enabled, { on -> ctx.run { ctx.library.setSourceEnabled(s.key, on); null } })
                    }
                    Hint("${s.type.name} · ${s.topic.ifBlank { "no default topic" }} · ${s.url}")
                    Text(
                        when {
                            !s.enabled -> "Paused."
                            s.lastError != null -> "Unavailable: ${s.lastError} Earlier items are kept; retrying " + (s.nextCheck?.let { "after ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it))}" } ?: "soon") + "."
                            s.lastSuccess != null -> "Healthy · ${s.itemCount} items in the last fetch · checked ${formatDate(s.lastChecked ?: s.lastSuccess ?: 0L)}"
                            else -> "Not checked yet."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.lastError != null && s.enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { ctx.run { ctx.library.removeSource(s.key); "Unsubscribed from ${s.name}. Stored items remain until cleanup." } }) { Text("Unsubscribe") }
                }
            }
        }
        HorizontalDivider()
        SectionTitle("Add a feed")
        var url by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var type by remember { mutableStateOf(ContentType.Blog) }
        var topic by remember { mutableStateOf("") }
        OutlinedTextField(url, { url = it }, label = { Text("Feed URL (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(ContentType.Blog, ContentType.News, ContentType.Paper).forEach { t -> FilterChip(type == t, { type = t }, { Text(t.name) }) }
        }
        Hint("Default topic for items that match none of your topics:")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            topics.orEmpty().forEach { t -> FilterChip(topic == t, { topic = t }, { Text(t) }) }
        }
        Button(onClick = {
            val u = url; val n = name; val ty = type; val tp = topic
            ctx.run {
                val key = ctx.library.addSource(u, n, ty, tp)
                url = ""; name = ""
                val report = PhoneLibrary.refresher(ctx.activity).refresh()
                val result = report.sources.firstOrNull { it.key == key }
                if (result?.error != null) "Added, but the first fetch failed: ${result.error}" else "Added $n with ${result?.newItems ?: 0} new items."
            }
        }, enabled = url.isNotBlank() && name.isNotBlank() && topic.isNotBlank() && !ctx.busy) { Text("Add and fetch") }
        Hint("Only public HTTPS feeds (RSS, Atom, JSON Feed). The app stores excerpts only and links to the original publisher.")
    }
}
