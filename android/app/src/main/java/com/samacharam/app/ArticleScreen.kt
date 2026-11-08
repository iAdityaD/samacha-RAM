package com.samacharam.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.samacharam.app.ui.ActionSheet
import com.samacharam.app.ui.Hairline
import com.samacharam.app.ui.Kicker
import com.samacharam.app.ui.PrimaryButton
import com.samacharam.app.ui.Radius
import com.samacharam.app.ui.SaveToggle
import com.samacharam.app.ui.SecondaryButton
import com.samacharam.app.ui.SectionLabel
import com.samacharam.app.ui.Space
import com.samacharam.app.ui.TypeRole
import com.samacharam.app.ui.colors
import com.samacharam.app.ui.hostOf
import com.samacharam.app.ui.sourceLabel
import com.samacharam.app.ui.timeAgo
import com.samacharam.core.ReadingEvent
import com.samacharam.core.ai.AiTask
import com.samacharam.core.ai.Location
import com.samacharam.core.ai.ModelResult
import com.samacharam.core.ai.TaskInput
import com.samacharam.core.ai.TaskOutput
import com.samacharam.core.formatDate

private data class AiAnswer(val title: String, val provenance: String, val lines: List<Pair<String, String?>>, val notice: String?)

private fun provenance(r: ModelResult): String {
    val where = when (r.location) { Location.Device -> "on this phone"; Location.LocalNetwork -> "on your network"; Location.Cloud -> "in the cloud" }
    return "${r.providerName}, $where · ${r.milliseconds} ms" + if (r.fallbackUsed) " · fallback used" else ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleScreen(ctx: AppContext, nav: NavHostController, id: Long) {
    val article by rememberLoaded(ctx, id) { ctx.library.article(id) }
    val saved by rememberLoaded(ctx, id) { ctx.library.isSaved(id) }
    val storedNote by rememberLoaded(ctx, id) { ctx.library.note(id) }
    val related by rememberLoaded(ctx, id) { ctx.library.related(id) }
    var note by remember(storedNote) { mutableStateOf(storedNote ?: "") }
    var asking by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf(false) }

    // Opening the page is recorded as a metadata view, never as a full read.
    LaunchedEffect(id) { ctx.quick { ctx.library.recordEvent(id, ReadingEvent.MetadataOpen, System.currentTimeMillis()); null } }
    val a = article

    AppPage(ctx, a?.sourceLabel() ?: "", nav = nav, actions = {
        if (a != null) {
            SaveToggle(saved == true) { ctx.toggleSave(id) }
            IconButton(onClick = { ctx.share(a) }) { Icon(Icons.Outlined.Share, contentDescription = "Share", tint = colors.ink) }
            IconButton(onClick = { actions = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More actions", tint = colors.ink) }
        }
    }) { padding ->
        if (a == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.evergreen) }
            return@AppPage
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Kicker(listOf(a.type.name, a.topics.joinToString(" · ")).filter { it.isNotBlank() }.joinToString("  ·  "), color = colors.evergreen)
                    Text(a.title, style = TypeRole.headline, color = colors.ink, modifier = Modifier.semantics { heading() })
                    Text(
                        listOfNotNull(a.authors.ifBlank { null }, a.published?.let(::formatDate) ?: "Date unknown", "${a.minutes} min read").joinToString(" · "),
                        style = TypeRole.meta, color = colors.inkMuted,
                    )
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(16.dp).padding(top = 2.dp), tint = colors.inkMuted)
                        Spacer(Modifier.width(Space.s))
                        Text(
                            (if (a.excerpt.isBlank()) "The publisher's feed gave a link only." else "This is the publisher's excerpt, not the full text.") +
                                " Claims are not assessed by samacha·RAM.",
                            style = TypeRole.meta.copy(fontWeight = TypeRole.ui.fontWeight), color = colors.inkMuted,
                        )
                    }
                }
                Hairline()
                if (a.excerpt.isNotBlank()) {
                    Text(a.excerpt, style = TypeRole.body, color = colors.ink, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.xl))
                }

                SectionLabel("Your note")
                if (saved == true) {
                    Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        OutlinedTextField(
                            note, { note = it }, placeholder = { Text("What matters here?", style = TypeRole.ui) }, minLines = 3, textStyle = TypeRole.ui,
                            shape = RoundedCornerShape(Radius.control), modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = colors.paperRaised, unfocusedContainerColor = colors.paperSunk,
                                unfocusedBorderColor = colors.paperSunk, focusedBorderColor = colors.evergreen),
                        )
                        if (note != (storedNote ?: "")) Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            SecondaryButton("Discard", { note = storedNote ?: "" })
                            PrimaryButton("Save note", { ctx.quick { ctx.library.save(id, System.currentTimeMillis(), note); "Note saved" } })
                        }
                    }
                } else {
                    Text("Save this item to keep notes with it.", style = TypeRole.ui, color = colors.inkMuted,
                        modifier = Modifier.padding(horizontal = Space.gutter).clickable(role = Role.Button) { ctx.toggleSave(id) })
                }

                if (!related.isNullOrEmpty()) {
                    SectionLabel("On the same topics")
                    Text("Shared topics only — not a verified connection.", style = TypeRole.meta, color = colors.inkMuted,
                        modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.xs))
                    related.orEmpty().forEach { r ->
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.navigate(Routes.article(r.id)) }.padding(horizontal = Space.gutter, vertical = Space.m),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Kicker("${r.sourceLabel()} · ${timeAgo(r.published)}")
                                Text(r.title, style = TypeRole.story, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Outlined.ChevronRight, null, tint = colors.inkFaint)
                        }
                        Hairline()
                    }
                }
                Spacer(Modifier.height(Space.xxl))
            }

            // One sticky bar with the primary action; Ask only when there is text to ask about.
            Surface(color = colors.paper) {
                Column {
                    HorizontalDivider(thickness = 1.dp, color = colors.rule)
                    Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        PrimaryButton("Read at ${hostOf(a.url)}", { ctx.openOriginal(a) }, Modifier.weight(1f), icon = Icons.AutoMirrored.Outlined.OpenInNew)
                        if (a.excerpt.isNotBlank()) SecondaryButton("Ask", { asking = true }, icon = Icons.Outlined.AutoAwesome)
                    }
                }
            }
        }
    }

    if (actions && a != null) ActionSheet(a.title, storyActions(ctx, a).drop(1), { actions = false })
    if (asking && a != null) AskSheet(ctx, id, a.title, a.excerpt) { asking = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskSheet(ctx: AppContext, id: Long, title: String, excerpt: String, onDismiss: () -> Unit) {
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<AiAnswer?>(null) }
    var working by remember { mutableStateOf(false) }

    fun ask(task: AiTask, label: String) {
        working = true
        ctx.quick {
            try {
                val r = ctx.router.run(task, TaskInput((title + "\n\n" + excerpt).trim(), question))
                if (task == AiTask.Summarization) ctx.library.recordEvent(id, ReadingEvent.SummaryOpen, System.currentTimeMillis())
                answer = when (val o = r.output) {
                    is TaskOutput.Cited -> AiAnswer(label, provenance(r), o.sentences.map { it.text to it.quote }.ifEmpty { listOf("The excerpt does not answer this." to null) }, o.notice)
                    is TaskOutput.Labels -> AiAnswer(label, provenance(r), listOf(o.labels.joinToString().ifEmpty { "No matching topics" } to null), o.notice)
                    is TaskOutput.Score -> AiAnswer(label, provenance(r), listOf("Relevance %.2f — %s".format(o.score, o.reason) to null), null)
                    is TaskOutput.Vectors -> AiAnswer(label, provenance(r), listOf("${o.dimensions}-dimension vector" to null), null)
                }
                null
            } finally { working = false }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.paperRaised) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = Space.xxl).padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text("Ask about this excerpt", style = TypeRole.headline, color = colors.ink)
            Text("Answers quote the excerpt and are checked against it. They use your Summaries and Q&A models, or built-in rules.",
                style = TypeRole.meta.copy(fontWeight = TypeRole.ui.fontWeight), color = colors.inkMuted)
            SecondaryButton("Summarize", { ask(AiTask.Summarization, "Summary") }, enabled = !working, icon = Icons.Outlined.AutoAwesome)
            OutlinedTextField(
                question, { question = it }, placeholder = { Text("Ask a question", style = TypeRole.ui) }, textStyle = TypeRole.ui, singleLine = true,
                shape = RoundedCornerShape(Radius.control), modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { ask(AiTask.QA, "Answer") }, enabled = !working && question.isNotBlank()) {
                        Icon(Icons.Outlined.QuestionAnswer, contentDescription = "Ask", tint = if (question.isNotBlank()) colors.evergreen else colors.inkFaint)
                    }
                },
            )
            if (working) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.evergreen); Spacer(Modifier.width(Space.m))
                Text("Reading the excerpt…", style = TypeRole.ui, color = colors.inkMuted)
            }
            answer?.let { r ->
                Column(
                    Modifier.fillMaxWidth().background(colors.paperSunk, RoundedCornerShape(Radius.control)).padding(Space.l),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    Kicker(r.title, color = colors.evergreen)
                    r.lines.forEach { (text, quote) ->
                        Text(text, style = TypeRole.ui, color = colors.ink)
                        quote?.takeIf { it != text }?.let { QuoteBlock(it, null, serif = true) }
                    }
                    Text(r.provenance, style = TypeRole.meta, color = colors.inkMuted)
                    r.notice?.let { Text(it, style = TypeRole.meta.copy(fontWeight = TypeRole.ui.fontWeight), color = colors.inkMuted) }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done", style = TypeRole.title, color = colors.evergreen) }
        }
    }
}
