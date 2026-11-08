package com.samacharam.app

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.compose.foundation.shape.RoundedCornerShape
import com.samacharam.app.ui.Pill
import com.samacharam.app.ui.Radius
import com.samacharam.app.ui.Space
import com.samacharam.app.ui.colors
import com.samacharam.app.ui.TypeRole
import com.samacharam.app.ui.ConfirmDialog
import com.samacharam.app.ui.MenuRow
import com.samacharam.app.ui.SectionHeader
import com.samacharam.app.ui.SettingRow
import com.samacharam.app.ui.SettingsGroup
import com.samacharam.app.ui.SwitchRow
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

private val PROTOCOL_LABELS = mapOf(
    Protocol.OpenAiCompatible to "OpenAI-compatible", Protocol.Anthropic to "Anthropic Claude", Protocol.Gemini to "Google Gemini",
    Protocol.Ollama to "Ollama", Protocol.OnDeviceLiteRt to "On-device · LiteRT-LM", Protocol.OnDeviceOnnx to "On-device · ONNX",
)

private val TASK_LABELS = mapOf(
    AiTask.Classification to "Topic tagging", AiTask.Summarization to "Summaries", AiTask.Embeddings to "Semantic search",
    AiTask.Reranking to "Reranking", AiTask.Evidence to "Evidence drafts", AiTask.QA to "Questions & answers",
)

private fun ProviderConfig.where(): Pair<ImageVector, String> = runCatching {
    when (location()) {
        Location.Device -> Icons.Outlined.Memory to "On this phone"
        Location.LocalNetwork -> Icons.Outlined.Lan to "Your network"
        Location.Cloud -> Icons.Outlined.Cloud to "Cloud"
    }
}.getOrDefault(Icons.Outlined.CloudOff to "Invalid endpoint")

@Composable
fun ModelsScreen(ctx: AppContext, nav: NavHostController) {
    val settings by rememberLoaded(ctx) { ctx.library.aiSettings() }
    val keyed by rememberLoaded(ctx) { ctx.library.aiSettings().providers.filter { ctx.secrets.get(it.id) != null }.map { it.id }.toSet() }
    var removing by remember { mutableStateOf<ProviderConfig?>(null) }
    AppPage(ctx, "Model connections", nav = nav, floating = {
        ExtendedFloatingActionButton(onClick = { nav.navigate(Routes.ADD_MODEL) }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add model", style = TypeRole.title) },
            containerColor = colors.evergreen, contentColor = colors.onEvergreen)
    }) { padding ->
        val s = settings ?: return@AppPage
        PageColumn(padding) {
            Text("Recommendations use rules you control. Optionally connect a model per task — on this phone, on your own computer, or a cloud API with your key.",
                style = TypeRole.ui, color = colors.inkMuted, modifier = Modifier.padding(horizontal = Space.gutter))
            SectionHeader("Privacy and cost")
            SettingsGroup {
                SwitchRow("Local only", s.policy.localOnly, Icons.Outlined.CloudOff, "Block every cloud model before any text is sent") { on ->
                    ctx.quick { ctx.library.saveAiSettings(s.copy(policy = s.policy.copy(localOnly = on))); null }
                }
                SwitchRow("Allow cloud fallback", s.policy.allowCloudFallback, Icons.Outlined.SwapVert, "If a local model fails, a cloud fallback may receive the text") { on ->
                    ctx.quick { ctx.library.saveAiSettings(s.copy(policy = s.policy.copy(allowCloudFallback = on))); null }
                }
                SwitchRow("Model-assisted briefing order", s.policy.assistedRanking, Icons.Outlined.Rule, "Your reranking model may reorder items within a layer") { on ->
                    ctx.quick { ctx.library.saveAiSettings(s.copy(policy = s.policy.copy(assistedRanking = on))); null }
                }
                var editing by remember { mutableStateOf(false) }
                SettingRow("Daily cloud request limit", Icons.Outlined.Speed, "${s.policy.dailyCloudRequests} requests per day", onClick = { editing = true })
                if (editing) {
                    var text by remember { mutableStateOf(s.policy.dailyCloudRequests.toString()) }
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { editing = false }, title = { Text("Daily cloud request limit") },
                        text = { OutlinedTextField(text, { text = it.filter(Char::isDigit).take(6) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(Radius.control), colors = formFieldColors()) },
                        confirmButton = { TextButton(onClick = { editing = false; val n = text.toIntOrNull() ?: 100; ctx.quick { ctx.library.saveAiSettings(s.copy(policy = s.policy.copy(dailyCloudRequests = n))); "Limit set to $n per day" } }) { Text("Save") } },
                        dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
                    )
                }
            }
            SectionHeader("Tasks")
            SettingsGroup {
                SettingRow("Choose a model for each task", Icons.Outlined.AltRoute,
                    AiTask.entries.count { s.route(it).primary != BUILTIN }.let { if (it == 0) "All tasks use built-in rules" else "$it of 6 tasks use a connected model" },
                    onClick = { nav.navigate(Routes.ROUTES) })
            }
            SectionHeader("Connected models")
            if (s.providers.isEmpty()) Text("None yet. Tap Add model to connect one.", style = TypeRole.ui, color = colors.inkMuted, modifier = Modifier.padding(horizontal = Space.gutter))
            if (s.providers.isNotEmpty()) SettingsGroup {
                s.providers.forEach { p ->
                    var menu by remember { mutableStateOf(false) }
                    val (icon, where) = p.where()
                    SettingRow(p.name, icon,
                        "${PROTOCOL_LABELS[p.protocol]} · ${p.model} · $where" + (if (p.id in keyed.orEmpty()) " · key saved" else "") +
                            "\n" + p.capabilities.sortedBy { it.ordinal }.joinToString { TASK_LABELS.getValue(it) }) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Model actions") }
                            DropdownMenu(menu, { menu = false }) {
                                MenuRow("Test connection", Icons.Outlined.NetworkCheck) {
                                    menu = false
                                    ctx.run {
                                        val r = ModelSetup.test(ctx.router, p)
                                        val detail = when (val o = r.output) {
                                            is TaskOutput.Cited -> o.sentences.firstOrNull()?.text ?: "valid reply"
                                            is TaskOutput.Labels -> o.labels.joinToString().ifEmpty { "valid reply" }
                                            is TaskOutput.Score -> "score %.2f".format(o.score)
                                            is TaskOutput.Vectors -> "${o.dimensions} dimensions"
                                        }
                                        "${p.name} works (${r.milliseconds} ms): $detail"
                                    }
                                }
                                MenuRow("Remove", Icons.Outlined.Delete) { menu = false; removing = p }
                            }
                        }
                    }
                }
            }
            DeviceModelImport(ctx)
        }
    }
    removing?.let { p ->
        ConfirmDialog("Remove ${p.name}?", "Its key is deleted from this phone and tasks using it go back to built-in rules.", "Remove", destructive = true,
            onDismiss = { removing = null }) { ctx.quick { ModelSetup.remove(ctx.library, ctx.secrets, p.id); "Removed ${p.name}" } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceModelImport(ctx: AppContext) {
    var bundle by remember { mutableStateOf("my-model") }
    var file by remember { mutableStateOf("model.litertlm") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { val b = bundle; val f = file; ctx.run { val hash = ctx.device.importFile(uri, b, f); "Imported $f into $b (sha256 ${hash.take(12)}…)" } }
    }
    SectionHeader("On-device model files")
    Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(bundle, { bundle = it }, label = { Text("Bundle name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.control), colors = formFieldColors())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("model.litertlm", "model.onnx", "vocab.txt", "config.json").forEach { f -> Pill(f, file == f, { file = f }) }
        }
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !ctx.busy) { Icon(Icons.Outlined.UploadFile, null); Text("  Choose file") }
        Text("LiteRT-LM (.litertlm) handles summaries, answers, and evidence drafts. ONNX needs model.onnx, vocab.txt, and config.json from an uncased BERT model. Check each model's license.",
            style = TypeRole.meta, color = colors.inkMuted)
    }
}

@Composable
private fun <T> Dropdown(label: String, value: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label: $value", maxLines = 1) }
        DropdownMenu(open, { open = false }) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(key) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutesScreen(ctx: AppContext, nav: NavHostController) {
    val settings by rememberLoaded(ctx) { ctx.library.aiSettings() }
    AppPage(ctx, "Tasks", nav = nav) { padding ->
        val s = settings ?: return@AppPage
        PageColumn(padding) {
            Text("Each task uses its primary model, then its fallback if that fails. Built-in rules are free and offline; semantic search, reranking, and evidence drafts need a model.",
                style = TypeRole.ui, color = colors.inkMuted, modifier = Modifier.padding(horizontal = Space.gutter))
            AiTask.entries.forEach { task ->
                val route = s.route(task)
                val options = listOf(BUILTIN to "Built-in rules") + s.providers.filter { task in it.capabilities }.map { it.id to it.name }
                fun name(id: String?) = options.firstOrNull { it.first == id }?.second ?: "none"
                SectionHeader(TASK_LABELS.getValue(task))
                FlowRow(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dropdown("Primary", name(route.primary), options) { id -> ctx.quick { ctx.library.saveAiSettings(s.withRoute(task, Route(id, route.fallback?.takeIf { it != id }))); null } }
                    Dropdown("Fallback", name(route.fallback), listOf<Pair<String?, String>>(null to "none") + options.filter { it.first != route.primary }) { id ->
                        ctx.quick { ctx.library.saveAiSettings(s.withRoute(task, Route(route.primary, id))); null }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddModelScreen(ctx: AppContext, nav: NavHostController) {
    val bundles by rememberLoaded(ctx) { ctx.device.names() }
    var protocol by remember { mutableStateOf(Protocol.OpenAiCompatible) }
    var name by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var tasks by remember { mutableStateOf(setOf<AiTask>()) }
    var allowCloud by remember { mutableStateOf(false) }
    AppPage(ctx, "Add model", nav = nav) { padding ->
        PageColumn(padding) {
            Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Type", style = TypeRole.title, color = colors.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Protocol.entries.forEach { p -> Pill(PROTOCOL_LABELS.getValue(p), protocol == p, { protocol = p; tasks = tasks intersect p.supports }) }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.control), colors = formFieldColors())
                if (protocol.onDevice) {
                    Dropdown("Bundle", model.ifBlank { "choose" }, bundles.orEmpty().map { it to it }) { model = it }
                    if (bundles.isNullOrEmpty()) Text("Import a model bundle on the previous screen first.", style = TypeRole.meta, color = colors.danger)
                } else {
                    OutlinedTextField(model, { model = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (protocol == Protocol.Ollama) "Model name, e.g. llama3.2" else "Model ID from the provider") }, shape = RoundedCornerShape(Radius.control), colors = formFieldColors())
                    OutlinedTextField(endpoint, { endpoint = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Endpoint") },
                        placeholder = { Text(defaultEndpoint(protocol) ?: if (protocol == Protocol.Ollama) "http://192.168.1.20:11434" else "https://api.openai.com/v1") },
                        supportingText = { defaultEndpoint(protocol)?.let { Text("Leave empty to use $it") } }, shape = RoundedCornerShape(Radius.control), colors = formFieldColors())
                    OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Key, null) },
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Stored only in this phone's Android Keystore. Optional for local servers.") }, shape = RoundedCornerShape(Radius.control), colors = formFieldColors())
                }
                Text("Tasks", style = TypeRole.title, color = colors.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    protocol.supports.sortedBy { it.ordinal }.forEach { t -> Pill(TASK_LABELS.getValue(t), t in tasks, { tasks = if (t in tasks) tasks - t else tasks + t }) }
                }
                if (!protocol.onDevice) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allowCloud, { allowCloud = it })
                    Text("If this is a cloud service, I allow sending article text to it.", style = TypeRole.ui, color = colors.ink)
                }
                Button(onClick = {
                    val config = ProviderConfig(UUID.randomUUID().toString(), name.trim(), protocol, model.trim(), endpoint.ifBlank { null }, tasks, allowCloud = allowCloud)
                    val secret = key
                    ctx.run {
                        ModelSetup.save(ctx.library, ctx.secrets, config, secret)
                        ctx.activity.runOnUiThread { nav.popBackStack() }
                        "Added ${config.name}. Assign it to a task, then use Test connection."
                    }
                }, enabled = name.isNotBlank() && model.isNotBlank() && tasks.isNotEmpty() && !ctx.busy, modifier = Modifier.fillMaxWidth()) { Text("Save model") }
                Text("Plain http:// is accepted only for devices on your own network. Cloud endpoints must use HTTPS.",
                    style = TypeRole.meta, color = colors.inkMuted)
            }
        }
    }
}
