package com.samacharam.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.samacharam.core.Article
import com.samacharam.core.FeedMode
import com.samacharam.core.FeedbackReason
import com.samacharam.core.MarkdownExport
import com.samacharam.core.ReadingEvent
import com.samacharam.core.ai.AiTask
import com.samacharam.core.ai.Location
import com.samacharam.core.ai.ModelResult
import com.samacharam.core.ai.TaskInput
import com.samacharam.core.ai.TaskOutput
import com.samacharam.core.app.FeedQuery
import com.samacharam.core.app.Feeds
import com.samacharam.core.formatDate

private fun Article.meta(): String =
    listOfNotNull(sourceKey.ifBlank { null }, type.name, published?.let(::formatDate), "${minutes} min").joinToString(" · ")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(ctx: AppContext, open: (Long) -> Unit) {
    var mode by remember { mutableStateOf(FeedMode.ForYou) }
    var query by remember { mutableStateOf("") }
    var mission by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    // Model-assisted ordering costs requests, so it runs only when the owner asks for it.
    var assist by remember(ctx.version) { mutableStateOf(false) } // resets after any change, so it never re-runs silently
    val assistAvailable by rememberLoaded(ctx) { ctx.library.aiSettings().policy.assistedRanking }
    val briefing by rememberLoaded(ctx, assist) { Feeds.dailyBriefing(ctx.library, now, if (assist) ctx.router else null) }
    val missions by rememberLoaded(ctx) { Feeds.activeMissions(ctx.library, now) }
    val rows by rememberLoaded(ctx, mode, query, mission) { Feeds.today(ctx.library, FeedQuery(mode, query, missionId = mission), now) }

    ScreenColumn {
        Text("Follow your curiosity.", style = MaterialTheme.typography.headlineMedium)
        briefing?.let { (queue, assistError) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Today's briefing", style = MaterialTheme.typography.titleMedium)
                    if (queue.articles.isEmpty()) Text("You're caught up. Refresh sources or widen your interests for more.")
                    else Text("${queue.articles.size} items · about ${queue.minutes} of ${queue.budgetMinutes} minutes (excerpt reading time)")
                    queue.articles.forEach { a -> TextButton(onClick = { open(a.id) }) { Text(a.title, maxLines = 2) } }
                    assistError?.let { Hint("Model-assisted ordering stopped early: $it Rule-based order kept for the rest.") }
                    if (assistAvailable == true && !assist && queue.articles.size > 1) {
                        TextButton(onClick = { assist = true }) { Text("Order within layers with my reranking model") }
                    }
                    if (assist) Hint("Ordered within each layer by your reranking model. Your layers and mutes still apply.")
                }
            }
        }
        Button(onClick = { ctx.run { PhoneLibrary.summary(PhoneLibrary.refresher(ctx.activity).refresh()) } }, enabled = !ctx.busy) {
            Text("Refresh sources")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == FeedMode.ForYou, { mode = FeedMode.ForYou }, { Text("For you") })
            FilterChip(mode == FeedMode.Following, { mode = FeedMode.Following; mission = null }, { Text("Following") })
            missions.orEmpty().forEach { m ->
                FilterChip(mission == m.id, { mission = if (mission == m.id) null else m.id; mode = FeedMode.ForYou }, { Text("Mission: ${m.title}") })
            }
        }
        OutlinedTextField(query, { query = it }, label = { Text("Search titles and excerpts") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val list = rows
        when {
            list == null -> Hint("Loading…")
            list.isEmpty() -> Hint(if (query.isBlank()) "Nothing here yet. Tap Refresh sources, or check Sources for errors." else "No matches.")
            else -> list.forEach { row ->
                Card(onClick = { open(row.article.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.article.title, style = MaterialTheme.typography.titleMedium)
                        Hint(row.article.meta())
                        Text(row.reason, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(ctx: AppContext, open: (Long) -> Unit) {
    val items by rememberLoaded(ctx) { ctx.library.savedItems() }
    ScreenColumn {
        Text("Keep the good things.", style = MaterialTheme.typography.headlineMedium)
        OutlinedButton(onClick = {
            ctx.run {
                val files = MarkdownExport.export(ctx.library.savedItems())
                if (files.isEmpty()) return@run "Save something first."
                val text = files.entries.joinToString("\n\n---\n\n") { (path, body) -> "<!-- $path -->\n$body" }
                ctx.activity.runOnUiThread {
                    ctx.activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_TEXT, text), "Export notes as Markdown"))
                }
                null
            }
        }) { Text("Export notes as Markdown") }
        val list = items
        when {
            list == null -> Hint("Loading…")
            list.isEmpty() -> Hint("Saved items, notes, and highlights appear here. Share a link to samacha·RAM to save it.")
            else -> list.forEach { item ->
                Card(onClick = { open(item.article.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.article.title, style = MaterialTheme.typography.titleMedium)
                        Hint("Saved ${formatDate(item.savedAt)} · ${item.article.meta()}")
                        if (item.note.isNotBlank()) Text(item.note, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                        if (item.highlights.isNotEmpty()) Hint("${item.highlights.size} highlight(s)")
                    }
                }
            }
        }
    }
}

private fun describe(result: ModelResult): String {
    val where = when (result.location) { Location.Device -> "on this phone"; Location.LocalNetwork -> "on your network"; Location.Cloud -> "in the cloud" }
    val fallback = if (result.fallbackUsed) " (fallback after: ${result.priorFailures.joinToString("; ")})" else ""
    return "${result.providerName} · ${result.model} · $where · ${result.milliseconds} ms$fallback"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArticleDialog(ctx: AppContext, id: Long, close: () -> Unit) {
    val now = System.currentTimeMillis()
    val article by rememberLoaded(ctx, id) { ctx.library.article(id) }
    val saved by rememberLoaded(ctx, id) { ctx.library.isSaved(id) }
    val storedNote by rememberLoaded(ctx, id) { ctx.library.note(id) }
    var note by remember(storedNote) { mutableStateOf(storedNote ?: "") }
    var question by remember { mutableStateOf("") }
    var output by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    val a = article

    fun runTask(task: AiTask, event: ReadingEvent?) = ctx.run {
        val item = a ?: return@run null
        val result = ctx.router.run(task, TaskInput((item.title + "\n\n" + item.excerpt).trim(), question))
        event?.let { ctx.library.recordEvent(id, it, System.currentTimeMillis()) }
        val lines = when (val o = result.output) {
            is TaskOutput.Cited -> o.sentences.map { "${it.text}\n   “${it.quote}”" }.ifEmpty { listOf("The excerpt does not answer this.") } + o.notice
            is TaskOutput.Labels -> listOf(o.labels.joinToString().ifEmpty { "No matching topics." }, o.notice)
            is TaskOutput.Score -> listOf("Relevance ${"%.2f".format(o.score)}: ${o.reason}")
            is TaskOutput.Vectors -> listOf("${o.dimensions}-dimension vector")
        }
        output = describe(result) to lines
        null
    }

    AlertDialog(
        onDismissRequest = close,
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
        title = { Text(a?.title ?: "Loading…") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (a == null) return@Column
                Hint(a.meta())
                Hint("Evidence: not assessed. Access: " + if (a.excerpt.isBlank()) "link only." else "feed excerpt only, not the full text.")
                if (a.excerpt.isNotBlank()) Text(a.excerpt, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        ctx.run { ctx.library.recordEvent(id, ReadingEvent.OriginalOpen, now); null }
                        ctx.activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url)))
                    }) { Text("Open original") }
                    OutlinedButton(onClick = {
                        ctx.run { if (saved == true) { ctx.library.unsave(id); "Removed from library." } else { ctx.library.save(id, now); "Saved." } }
                    }) { Text(if (saved == true) "Unsave" else "Save") }
                    OutlinedButton(onClick = { ctx.run { if (ctx.library.recordEvent(id, ReadingEvent.MarkRead, now)) "Marked as read." else "Tracking is paused; nothing recorded." } }) {
                        Text("Mark read")
                    }
                }
                HorizontalDivider()
                Text("Ask about this excerpt", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { runTask(AiTask.Summarization, ReadingEvent.SummaryOpen) }, enabled = !ctx.busy && a.excerpt.isNotBlank()) { Text("Summarize") }
                }
                OutlinedTextField(question, { question = it }, label = { Text("Question") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { runTask(AiTask.QA, null) }, enabled = !ctx.busy && question.isNotBlank() && a.excerpt.isNotBlank()) { Text("Answer from the excerpt") }
                output?.let { (who, lines) ->
                    Hint(who)
                    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                HorizontalDivider()
                Text("Tune your feed", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        FeedbackReason.Useful to "More like this", FeedbackReason.LessRelevant to "Less like this",
                        FeedbackReason.Duplicate to "Already seen", FeedbackReason.PoorEvidence to "Weak evidence", FeedbackReason.NotNow to "Not now",
                    ).forEach { (reason, label) ->
                        TextButton(onClick = {
                            ctx.run { if (ctx.library.addFeedback(id, reason, now)) "Thanks. This only reorders items within your layers." else "Tracking is paused; feedback was not stored." }
                        }) { Text(label) }
                    }
                }
                if (saved == true) {
                    HorizontalDivider()
                    OutlinedTextField(note, { note = it }, label = { Text("Your note") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { ctx.run { ctx.library.save(id, now, note); "Note saved." } }) { Text("Save note") }
                }
            }
        },
    )
}
