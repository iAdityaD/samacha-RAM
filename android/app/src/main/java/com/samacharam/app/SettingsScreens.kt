package com.samacharam.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.samacharam.core.HostImport
import com.samacharam.core.ai.AiTask
import com.samacharam.core.ai.BUILTIN
import com.samacharam.core.ai.Location
import com.samacharam.core.ai.Protocol
import com.samacharam.core.ai.ProviderConfig
import com.samacharam.core.ai.Route
import com.samacharam.core.ai.TaskOutput
import com.samacharam.core.ai.defaultEndpoint
import com.samacharam.core.ai.location
import com.samacharam.core.ai.withRoute
import com.samacharam.core.app.ModelSetup
import java.util.UUID

private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)

@Composable
private fun SwitchRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Hint(it) }
        }
        Switch(checked, onChange)
    }
}

@Composable
fun SettingsScreen(ctx: AppContext, onModels: () -> Unit) {
    val stats by rememberLoaded(ctx) { ctx.library.stats() }
    val modelBytes by rememberLoaded(ctx) { ctx.device.root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    val settings by rememberLoaded(ctx) {
        listOf(ctx.library.tracking, ctx.library.briefingEnabled, ctx.library.meta("background.enabled") == "1") to ctx.library.briefingTime
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ctx.run {
            val bytes = ctx.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            require(bytes.size <= 50 * 1024 * 1024) { "Export files over 50 MB are not supported." }
            val r = HostImport.import(bytes.decodeToString(), ctx.library, System.currentTimeMillis())
            "Imported ${r.articles} items, ${r.saved} saved, ${r.highlights} highlights, ${r.sources} sources" + if (r.skipped > 0) "; ${r.skipped} entries skipped." else "."
        }
    }
    var confirmClear by remember { mutableStateOf(false) }

    ScreenColumn {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        val (flags, time) = settings ?: return@ScreenColumn
        val (tracking, briefing, background) = flags

        SectionTitle("Daily briefing")
        SwitchRow("Briefing reminder", briefing, "One notification a day after the chosen time, with your queue. Needs background refresh.") { on ->
            if (on && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            ctx.run { ctx.library.briefingEnabled = on; if (on) { ctx.library.setMeta("background.enabled", "1"); PhoneLibrary.enableBackgroundRefresh(ctx.activity) }; null }
        }
        var timeText by remember(time) { mutableStateOf(time) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(timeText, { timeText = it }, label = { Text("Time (24h, local)") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { val t = timeText.trim(); ctx.run { ctx.library.briefingTime = t; "Briefing time set to $t." } }) { Text("Set") }
        }
        SwitchRow("Refresh in the background", background, "Hourly while online and not low on battery. Android decides the exact time.") { on ->
            ctx.run {
                ctx.library.setMeta("background.enabled", if (on) "1" else "0")
                if (on) PhoneLibrary.enableBackgroundRefresh(ctx.activity) else PhoneLibrary.disableBackgroundRefresh(ctx.activity)
                null
            }
        }

        HorizontalDivider()
        SectionTitle("AI models")
        Hint("Recommendations use rules. Optionally connect a model per task: on this phone, on your computer, or a cloud API with your key.")
        Button(onClick = onModels) { Text("Model connections") }

        HorizontalDivider()
        SectionTitle("Privacy")
        SwitchRow("Record reading history and feedback", tracking, "When off, opening, marking read, and feedback are not stored.") { on ->
            ctx.run { ctx.library.tracking = on; null }
        }
        OutlinedButton(onClick = { confirmClear = true }) { Text("Clear history and feedback") }
        Hint("Everything stays on this phone. API keys are kept in the Android Keystore and are never exported.")

        HorizontalDivider()
        SectionTitle("Storage")
        stats?.let { s ->
            Text("Library: ${mb(s.bytes)} · ${s.articles} items (${s.trimmedArticles} older items kept as title only) · ${s.saved} saved · ${s.highlights} highlights")
            Hint("Excerpt text ${mb(s.excerptBytes)} compressed · ${s.history} history events · ${s.feedback} feedback · on-device models ${mb(modelBytes ?: 0)}")
        }
        Hint("Unsaved items keep their excerpt for 30 days and are removed after 90. Saved and highlighted items are never removed.")
        OutlinedButton(onClick = {
            ctx.run {
                val r = ctx.library.applyRetention(System.currentTimeMillis())
                ctx.library.compact()
                "Cleaned up: ${r.excerptsDropped} excerpts trimmed, ${r.articlesDeleted} old items removed."
            }
        }) { Text("Clean up now") }

        HorizontalDivider()
        SectionTitle("Bring data from the computer app")
        Hint("In the web app, use Export to save samacha-library.json, copy it to this phone, then import it here. Nothing connects to your computer.")
        OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import export file") }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear history and feedback?") },
        text = { Text("Saved items and notes stay. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; ctx.run { ctx.library.clearHistory(); "History and feedback cleared." } }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun <T> Picker(label: String, value: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label: $value") }
        DropdownMenu(open, { open = false }) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(key) }) }
        }
    }
}

private val PROTOCOL_LABELS = mapOf(
    Protocol.OpenAiCompatible to "OpenAI-compatible API", Protocol.Anthropic to "Anthropic (Claude)", Protocol.Gemini to "Google Gemini",
    Protocol.Ollama to "Ollama", Protocol.OnDeviceLiteRt to "On this phone: LiteRT-LM", Protocol.OnDeviceOnnx to "On this phone: ONNX BERT",
)

private fun whereLabel(p: ProviderConfig) = runCatching {
    when (p.location()) { Location.Device -> "on this phone"; Location.LocalNetwork -> "your network"; Location.Cloud -> "cloud" }
}.getOrDefault("invalid endpoint")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelsScreen(ctx: AppContext, onBack: () -> Unit) {
    val settings by rememberLoaded(ctx) { ctx.library.aiSettings() }
    val keyed by rememberLoaded(ctx) { ctx.library.aiSettings().providers.filter { ctx.secrets.get(it.id) != null }.map { it.id }.toSet() }
    val bundles by rememberLoaded(ctx) { ctx.device.names() }
    ScreenColumn {
        TextButton(onClick = onBack) { Text("← Settings") }
        val s = settings ?: return@ScreenColumn
        Hint("Each task can use a different model, with an optional fallback. Built-in rules are free and offline. Model output is never treated as verified.")

        SectionTitle("Privacy and cost")
        SwitchRow("Local only", s.policy.localOnly, "Block every cloud model before any text is sent.") { on ->
            ctx.run { ctx.library.saveAiSettings(s.copy(policy = s.policy.copy(localOnly = on))); null }
        }
        SwitchRow("Allow cloud fallback", s.policy.allowCloudFallback, "If a local model fails, a cloud fallback may receive the text.") { on ->
            ctx.run { ctx.library.saveAiSettings(s.copy(policy = s.policy.copy(allowCloudFallback = on))); null }
        }
        SwitchRow("Model-assisted briefing order", s.policy.assistedRanking, "Your reranking model may reorder items within a layer when you ask on Today.") { on ->
            ctx.run { ctx.library.saveAiSettings(s.copy(policy = s.policy.copy(assistedRanking = on))); null }
        }
        var limit by remember(s.policy.dailyCloudRequests) { mutableStateOf(s.policy.dailyCloudRequests.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(limit, { limit = it.filter(Char::isDigit).take(6) }, label = { Text("Cloud requests per day") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { val n = limit.toIntOrNull() ?: 100; ctx.run { ctx.library.saveAiSettings(s.copy(policy = s.policy.copy(dailyCloudRequests = n))); "Daily limit set to $n." } }) { Text("Set") }
        }

        HorizontalDivider()
        SectionTitle("Which model does each task")
        AiTask.entries.forEach { task ->
            val route = s.route(task)
            val options = listOf(BUILTIN to "Built-in rules") + s.providers.filter { task in it.capabilities }.map { it.id to it.name }
            fun name(id: String?) = options.firstOrNull { it.first == id }?.second ?: "none"
            Text(task.wire.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker("Primary", name(route.primary), options) { id -> ctx.run { ctx.library.saveAiSettings(s.withRoute(task, Route(id, route.fallback?.takeIf { it != id }))); null } }
                Picker("Fallback", name(route.fallback), listOf<Pair<String?, String>>(null to "none") + options.filter { it.first != route.primary }) { id ->
                    ctx.run { ctx.library.saveAiSettings(s.withRoute(task, Route(route.primary, id))); null }
                }
            }
        }

        HorizontalDivider()
        SectionTitle("Connected models")
        if (s.providers.isEmpty()) Hint("None yet. Add one below.")
        s.providers.forEach { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Hint("${PROTOCOL_LABELS[p.protocol]} · ${p.model} · ${whereLabel(p)}" + if (p.id in keyed.orEmpty()) " · key saved" else "")
                    Hint("Tasks: " + p.capabilities.sortedBy { it.ordinal }.joinToString { it.wire })
                    Row {
                        TextButton(onClick = {
                            ctx.run {
                                val r = ModelSetup.test(ctx.router, p)
                                val detail = when (val o = r.output) {
                                    is TaskOutput.Cited -> o.sentences.firstOrNull()?.text ?: "valid reply, no sentences"
                                    is TaskOutput.Labels -> o.labels.joinToString().ifEmpty { "valid reply, no labels" }
                                    is TaskOutput.Score -> "score %.2f".format(o.score)
                                    is TaskOutput.Vectors -> "${o.dimensions} dimensions"
                                }
                                "Works (${r.milliseconds} ms): $detail"
                            }
                        }, enabled = !ctx.busy) { Text("Test") }
                        TextButton(onClick = { ctx.run { ModelSetup.remove(ctx.library, ctx.secrets, p.id); "Removed ${p.name} and its key." } }) { Text("Remove") }
                    }
                }
            }
        }

        HorizontalDivider()
        SectionTitle("Add a model")
        var protocol by remember { mutableStateOf(Protocol.OpenAiCompatible) }
        var name by remember { mutableStateOf("") }
        var model by remember { mutableStateOf("") }
        var endpoint by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        var tasks by remember { mutableStateOf(setOf<AiTask>()) }
        var allowCloud by remember { mutableStateOf(false) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Protocol.entries.forEach { p -> FilterChip(protocol == p, { protocol = p; tasks = tasks intersect p.supports }, { Text(PROTOCOL_LABELS.getValue(p)) }) }
        }
        OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (protocol.onDevice) {
            Picker("Bundle", model.ifBlank { "choose" }, bundles.orEmpty().map { it to it }) { model = it }
            if (bundles.isNullOrEmpty()) Hint("Import a model bundle below first.")
        } else {
            OutlinedTextField(model, { model = it }, label = { Text(if (protocol == Protocol.Ollama) "Model name, e.g. llama3.2" else "Model ID from the provider") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(endpoint, { endpoint = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = {
                Text(defaultEndpoint(protocol)?.let { "Endpoint (default $it)" } ?: if (protocol == Protocol.Ollama) "Endpoint, e.g. http://192.168.1.20:11434" else "Endpoint, e.g. https://api.openai.com/v1")
            })
            OutlinedTextField(key, { key = it }, label = { Text("API key (optional for local servers)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        }
        Hint("Tasks this model should handle:")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            protocol.supports.sortedBy { it.ordinal }.forEach { t -> FilterChip(t in tasks, { tasks = if (t in tasks) tasks - t else tasks + t }, { Text(t.wire) }) }
        }
        if (!protocol.onDevice) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(allowCloud, { allowCloud = it })
            Text("If this is a cloud service, I allow sending article text to it.", style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = {
            val config = ProviderConfig(
                id = UUID.randomUUID().toString(), name = name.trim(), protocol = protocol, model = model.trim(),
                endpoint = endpoint.trim().ifBlank { null }, capabilities = tasks, allowCloud = allowCloud,
            )
            val secret = key
            ctx.run {
                ModelSetup.save(ctx.library, ctx.secrets, config, secret)
                name = ""; model = ""; endpoint = ""; key = ""; tasks = emptySet(); allowCloud = false
                "Added ${config.name}. Assign it to a task above, then tap Test."
            }
        }, enabled = name.isNotBlank() && model.isNotBlank() && tasks.isNotEmpty() && !ctx.busy) { Text("Save model") }
        Hint("Keys are stored only in this phone's Android Keystore. Plain http:// is accepted only for devices on your own network.")

        HorizontalDivider()
        SectionTitle("Import an on-device model")
        var bundle by remember { mutableStateOf("my-model") }
        var file by remember { mutableStateOf("model.litertlm") }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) { val b = bundle; val f = file; ctx.run { val hash = ctx.device.importFile(uri, b, f); "Imported $f into $b (sha256 ${hash.take(12)}…)." } }
        }
        OutlinedTextField(bundle, { bundle = it }, label = { Text("Bundle name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("model.litertlm", "model.onnx", "vocab.txt", "config.json").forEach { f -> FilterChip(file == f, { file = f }, { Text(f) }) }
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !ctx.busy) { Text("Choose file") }
        Hint("LiteRT-LM (.litertlm) for summaries, answers, and evidence drafts. ONNX needs model.onnx, vocab.txt, and config.json (uncased BERT). Check each model's license.")
    }
}
