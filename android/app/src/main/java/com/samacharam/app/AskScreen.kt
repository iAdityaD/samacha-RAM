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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.samacharam.app.ui.Avatar
import com.samacharam.app.ui.Kicker
import com.samacharam.app.ui.Pill
import com.samacharam.app.ui.Space
import com.samacharam.app.ui.TabBar
import com.samacharam.app.ui.TypeRole
import com.samacharam.app.ui.colors
import com.samacharam.app.ui.sourceLabel
import com.samacharam.core.Article
import com.samacharam.core.Layer
import com.samacharam.core.ReadingEvent
import com.samacharam.core.ai.AiTask
import com.samacharam.core.ai.Location
import com.samacharam.core.ai.ModelResult
import com.samacharam.core.ai.TaskInput
import com.samacharam.core.ai.TaskOutput
import com.samacharam.core.db.Library

/** One turn in an Ask thread. Answers carry the quotes they rest on and where they were computed. */
data class AskMessage(
    val fromYou: Boolean, val text: String,
    val lines: List<Pair<String, String?>> = emptyList(), val provenance: String? = null,
    val sources: List<Article> = emptyList(), val notice: String? = null,
)

private fun provenance(r: ModelResult): String {
    val where = when (r.location) { Location.Device -> "on this phone"; Location.LocalNetwork -> "on your network"; Location.Cloud -> "in the cloud" }
    return "${r.providerName}, $where · ${r.milliseconds} ms" + if (r.fallbackUsed) " · fallback used" else ""
}

private val STOP = setOf("what", "whats", "the", "and", "for", "with", "about", "did", "does", "how", "why", "who", "are", "was", "were", "new", "any",
    "this", "that", "from", "have", "has", "can", "you", "your", "my", "me", "i", "is", "in", "on", "of", "to", "a", "an", "it", "say", "said", "tell", "there")

/** Finds the library items that best match a question: each keyword is searched, and items matching more keywords rank first. */
fun findForQuestion(library: Library, question: String, limit: Int = 5): List<Article> {
    val words = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 && it !in STOP }.distinct()
    if (words.isEmpty()) return emptyList()
    val hits = words.flatMap { w -> runCatching { library.search(w, 20) }.getOrDefault(emptyList()) }.groupingBy { it }.eachCount()
    return hits.entries.sortedByDescending { it.value }.take(limit).mapNotNull { library.article(it.key) }
}

/**
 * Ask: one conversation screen. Opened from a story it answers about that story; opened from the bar it answers from your library.
 */
@Composable
fun AskScreen(ctx: AppContext, nav: NavHostController, articleId: Long) {
    var detached by rememberSaveable(articleId) { mutableStateOf(false) }
    val id = if (detached) -1L else articleId
    val article by rememberLoaded(ctx, id) { if (id > 0) ctx.library.article(id) else null }
    val suggestions by rememberLoaded(ctx) {
        ctx.library.profile().topics.filterValues { it == Layer.Core || it == Layer.Near }.keys.take(2).flatMap { listOf("What's new in $it?") } + "What did I save this week?"
    }
    var input by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val thread = ctx.askThreads[id].orEmpty()
    val listState = rememberLazyListState()
    LaunchedEffect(thread.size, working) { if (thread.isNotEmpty()) listState.animateScrollToItem(thread.size + 1) }

    fun ask(question: String, task: AiTask, shown: String = question) {
        if (working || (question.isBlank() && task != AiTask.Summarization)) return
        val key = id
        val a = article
        ctx.askThreads[key] = ctx.askThreads[key].orEmpty() + AskMessage(true, shown)
        input = ""
        working = true
        ctx.quick {
            val reply = try {
                val sources = if (a != null) listOf(a) else findForQuestion(ctx.library, question)
                if (sources.isEmpty()) AskMessage(false, "Nothing in your library mentions that yet. Try other words, or open a story and ask about it.")
                else {
                    val text = sources.joinToString("\n\n") { (it.title + ". " + it.excerpt).trim() }
                    val r = ctx.router.run(task, TaskInput(text, question))
                    if (a != null && task == AiTask.Summarization) ctx.library.recordEvent(a.id, ReadingEvent.SummaryOpen, System.currentTimeMillis())
                    when (val o = r.output) {
                        is TaskOutput.Cited -> AskMessage(false, "", o.sentences.map { it.text to it.quote }.ifEmpty { listOf("The text does not answer this." to null) }, provenance(r), sources, o.notice)
                        is TaskOutput.Labels -> AskMessage(false, o.labels.joinToString().ifEmpty { "No matching topics" }, provenance = provenance(r), sources = sources, notice = o.notice)
                        is TaskOutput.Score -> AskMessage(false, "Relevance %.2f — %s".format(o.score, o.reason), provenance = provenance(r), sources = sources)
                        is TaskOutput.Vectors -> AskMessage(false, "${o.dimensions}-dimension vector", provenance = provenance(r))
                    }
                }
            } catch (e: Exception) {
                AskMessage(false, "Couldn't answer: ${e.message ?: "the model did not respond."}")
            } finally { working = false }
            ctx.askThreads[key] = ctx.askThreads[key].orEmpty() + reply
            null
        }
    }

    Column(Modifier.fillMaxSize().background(colors.paper).imePadding()) {
        TabBar("Ask") {
            if (thread.isNotEmpty()) IconButton(onClick = { ctx.askThreads.remove(id) }) { Icon(Icons.Outlined.Close, contentDescription = "Clear conversation", tint = colors.ink) }
            IconButton(onClick = { nav.navigate(Routes.MODELS) }) { Icon(Icons.Outlined.Tune, contentDescription = "Choose models", tint = colors.ink) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = Space.gutter, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.m)) {
            item(key = "context") {
                val a = article
                if (a != null) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.paperSunk).clickable(role = Role.Button) { nav.navigate(Routes.article(a.id)) }.padding(Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(a.sourceLabel(), 36.dp)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Kicker("Asking about")
                        Text(a.title, style = TypeRole.story.copy(fontSize = 15.sp, lineHeight = 20.sp), color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { detached = true }) { Icon(Icons.Outlined.Close, contentDescription = "Ask about your whole library instead", tint = colors.inkMuted) }
                } else Column(Modifier.padding(vertical = Space.s)) {
                    Text("Ask your library", style = TypeRole.headline, color = colors.ink)
                    Spacer(Modifier.height(Space.xs))
                    Text("Answers come only from stories on this phone and quote them. Open a story and tap Ask to question just that one.",
                        style = TypeRole.ui, color = colors.inkMuted)
                }
            }
            items(thread) { m -> Bubble(m) { nav.navigate(Routes.article(it)) } }
            if (working) item(key = "working") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.evergreen)
                    Spacer(Modifier.width(Space.s))
                    Text(if (article != null) "Reading the story…" else "Searching your library…", style = TypeRole.meta, color = colors.inkMuted)
                }
            }
        }
        // Suggested prompts sit right above the input, where the thumb already is.
        val chips: List<Pair<String, () -> Unit>> = if (article != null) listOf(
            "Summarize" to { ask("", AiTask.Summarization, "Summarize this") },
            "Key points" to { ask("What are the key points?", AiTask.QA) },
            "What's the evidence?" to { ask("What evidence supports the main claim?", AiTask.QA) },
        ) else suggestions.orEmpty().map { s -> s to { ask(s, AiTask.QA) } }
        LazyRow(contentPadding = PaddingValues(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(chips) { (label, go) -> Pill(label, false, { go() }, Icons.Outlined.AutoAwesome) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(colors.paperSunk).padding(horizontal = Space.l), contentAlignment = Alignment.CenterStart) {
                if (input.isEmpty()) Text(if (article != null) "Ask about this story" else "Ask about anything you've gathered", style = TypeRole.ui, color = colors.inkMuted)
                BasicTextField(input, { input = it }, textStyle = TypeRole.ui.copy(color = colors.ink), cursorBrush = SolidColor(colors.evergreen), maxLines = 4,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Question" })
            }
            IconButton(onClick = { ask(input.trim(), AiTask.QA) }, enabled = input.isNotBlank() && !working) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send", tint = if (input.isNotBlank()) colors.evergreen else colors.inkFaint)
            }
        }
    }
}

@Composable
private fun Bubble(m: AskMessage, open: (Long) -> Unit) {
    if (m.fromYou) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(m.text, style = TypeRole.ui, color = colors.onEvergreen,
            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).background(colors.evergreen).padding(horizontal = Space.l, vertical = Space.m))
    } else Column(
        Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, colors.rule, RoundedCornerShape(18.dp)).background(colors.paperRaised).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        if (m.text.isNotBlank()) Text(m.text, style = TypeRole.ui, color = colors.ink)
        m.lines.forEach { (text, quote) ->
            Text(text, style = TypeRole.ui, color = colors.ink)
            quote?.takeIf { it != text }?.let { QuoteBlock(it, null, serif = true) }
        }
        if (m.sources.size > 1 || (m.sources.size == 1 && m.lines.isNotEmpty() && m.sources.first().id > 0)) {
            Kicker("From")
            m.sources.forEach { s ->
                Text("${s.sourceLabel()} · ${s.title}", style = TypeRole.meta, color = colors.evergreen, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(role = Role.Button) { open(s.id) }.padding(vertical = 2.dp))
            }
        }
        m.provenance?.let { Text(it, style = TypeRole.meta, color = colors.inkMuted) }
        m.notice?.let { Text(it, style = TypeRole.meta, color = colors.inkMuted) }
    }
}
