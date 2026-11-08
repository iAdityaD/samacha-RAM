package com.samacharam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Unpublished
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.samacharam.app.ui.MenuRow
import com.samacharam.app.ui.Pill
import com.samacharam.app.ui.SectionHeader
import com.samacharam.app.ui.SourceAvatar
import com.samacharam.app.ui.TypePill
import com.samacharam.app.ui.hostOf
import com.samacharam.app.ui.timeAgo
import com.samacharam.core.FeedbackReason
import com.samacharam.core.ReadingEvent
import com.samacharam.core.ai.AiTask
import com.samacharam.core.ai.Location
import com.samacharam.core.ai.ModelResult
import com.samacharam.core.ai.TaskInput
import com.samacharam.core.ai.TaskOutput
import com.samacharam.core.formatDate

private data class AiAnswer(val title: String, val provenance: String, val lines: List<Pair<String, String?>>, val notice: String?)

private fun provenance(r: ModelResult): String {
    val where = when (r.location) { Location.Device -> "on this phone"; Location.LocalNetwork -> "on your network"; Location.Cloud -> "cloud" }
    return "${r.providerName} · $where · ${r.milliseconds} ms" + if (r.fallbackUsed) " · fallback used" else ""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArticleScreen(ctx: AppContext, nav: NavHostController, id: Long) {
    val article by rememberLoaded(ctx, id) { ctx.library.article(id) }
    val saved by rememberLoaded(ctx, id) { ctx.library.isSaved(id) }
    val storedNote by rememberLoaded(ctx, id) { ctx.library.note(id) }
    val related by rememberLoaded(ctx, id) { ctx.library.related(id) }
    var note by remember(storedNote) { mutableStateOf(storedNote ?: "") }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<AiAnswer?>(null) }
    var thinking by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    // Opening the detail page is a metadata view, not a full read.
    LaunchedEffect(id) { ctx.quick { ctx.library.recordEvent(id, ReadingEvent.MetadataOpen, System.currentTimeMillis()); null } }

    val a = article
    fun ask(task: AiTask, title: String) {
        val item = a ?: return
        thinking = true
        ctx.quick {
            try {
                val r = ctx.router.run(task, TaskInput((item.title + "\n\n" + item.excerpt).trim(), question))
                if (task == AiTask.Summarization) ctx.library.recordEvent(id, ReadingEvent.SummaryOpen, System.currentTimeMillis())
                answer = when (val o = r.output) {
                    is TaskOutput.Cited -> AiAnswer(title, provenance(r), o.sentences.map { it.text to it.quote }.ifEmpty { listOf("The excerpt does not answer this." to null) }, o.notice)
                    is TaskOutput.Labels -> AiAnswer(title, provenance(r), listOf((o.labels.joinToString().ifEmpty { "No matching topics" }) to null), o.notice)
                    is TaskOutput.Score -> AiAnswer(title, provenance(r), listOf("Relevance %.2f — %s".format(o.score, o.reason) to null), null)
                    is TaskOutput.Vectors -> AiAnswer(title, provenance(r), listOf("${o.dimensions}-dimension vector" to null), null)
                }
                null
            } finally {
                thinking = false
            }
        }
    }

    AppPage(ctx, "", nav = nav, large = false, actions = {
        if (a != null) {
            IconButton(onClick = { ctx.toggleSave(id) }) {
                Icon(if (saved == true) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, contentDescription = if (saved == true) "Unsave" else "Save",
                    tint = if (saved == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { ctx.share(a) }) { Icon(Icons.Outlined.Share, contentDescription = "Share") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More actions") }
                DropdownMenu(menu, { menu = false }) {
                    MenuRow("Mark as read", Icons.Outlined.DoneAll) { menu = false; ctx.markRead(id) }
                    MenuRow("More like this", Icons.Outlined.ThumbUp) { menu = false; ctx.feedback(id, FeedbackReason.Useful) }
                    MenuRow("Less like this", Icons.Outlined.ThumbDown) { menu = false; ctx.feedback(id, FeedbackReason.LessRelevant) }
                    MenuRow("Already seen this", Icons.Outlined.Info) { menu = false; ctx.feedback(id, FeedbackReason.Duplicate) }
                    MenuRow("Weak evidence", Icons.Outlined.Unpublished) { menu = false; ctx.feedback(id, FeedbackReason.PoorEvidence) }
                    MenuRow("Not now", Icons.Outlined.Schedule) { menu = false; ctx.feedback(id, FeedbackReason.NotNow) }
                }
            }
        }
    }) { padding ->
        if (a == null) {
            Box(Modifier.padding(padding).fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@AppPage
        }
        PageColumn(padding) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceAvatar(a.sourceKey.ifBlank { hostOf(a.url) }, a.sourceKey.ifBlank { a.url })
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(a.sourceKey.ifBlank { hostOf(a.url) }, style = MaterialTheme.typography.labelLarge)
                        Text(listOfNotNull(a.authors.ifBlank { null }, a.published?.let(::formatDate)).joinToString(" · ").ifEmpty { "Date unknown" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(a.title, style = MaterialTheme.typography.headlineSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TypePill(a.type)
                    a.topics.forEach { Pill(it.uppercase()) }
                    Pill("${a.minutes} MIN", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Evidence not assessed. " + if (a.excerpt.isBlank()) "Only the link is stored." else "Showing the publisher's feed excerpt, not the full text.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (a.excerpt.isNotBlank()) Text(a.excerpt, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { ctx.openOriginal(a) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Read at ${hostOf(a.url)}")
                }
            }

            SectionHeader("Ask about this excerpt")
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { ask(AiTask.Summarization, "Summary") }, enabled = !thinking && a.excerpt.isNotBlank()) {
                        Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Summarize")
                    }
                }
                OutlinedTextField(question, { question = it }, label = { Text("Ask a question") }, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { ask(AiTask.QA, "Answer") }, enabled = !thinking && question.isNotBlank() && a.excerpt.isNotBlank()) {
                            Icon(Icons.Outlined.QuestionAnswer, contentDescription = "Ask")
                        }
                    })
                if (thinking) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Working…")
                }
                answer?.let { r ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(r.title, style = MaterialTheme.typography.titleMedium)
                            r.lines.forEach { (text, quote) ->
                                Text(text, style = MaterialTheme.typography.bodyMedium)
                                quote?.takeIf { it != text }?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            HorizontalDivider()
                            Text(r.provenance, style = MaterialTheme.typography.labelMedium)
                            r.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }

            SectionHeader("Your notes")
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (saved == true) {
                    OutlinedTextField(note, { note = it }, placeholder = { Text("What matters here?") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Outlined.EditNote, null) })
                    OutlinedButton(onClick = { ctx.quick { ctx.library.save(id, System.currentTimeMillis(), note); "Note saved" } }, enabled = note != (storedNote ?: "")) { Text("Save note") }
                } else {
                    AssistChip(onClick = { ctx.toggleSave(id) }, label = { Text("Save this item to add notes") }, leadingIcon = { Icon(Icons.Outlined.BookmarkBorder, null) })
                }
            }

            if (!related.isNullOrEmpty()) {
                SectionHeader("On the same topics")
                related.orEmpty().forEach { r ->
                    ListItem(
                        headlineContent = { Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${r.sourceKey.ifBlank { hostOf(r.url) }} · ${timeAgo(r.published)} · shared topics, not a verified link") },
                        modifier = Modifier.padding(horizontal = 4.dp).let { m -> m.then(Modifier) },
                        trailingContent = { IconButton(onClick = { nav.navigate(Routes.article(r.id)) }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open") } },
                    )
                }
            }
        }
    }
}
