package com.samacharam.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.samacharam.app.ui.ConfirmDialog
import com.samacharam.app.ui.SectionHeader
import com.samacharam.app.ui.SettingRow
import com.samacharam.app.ui.SettingsGroup
import com.samacharam.app.ui.SwitchRow
import com.samacharam.app.ui.ThemeMode
import com.samacharam.app.ui.timeAgo
import com.samacharam.core.HostImport
import com.samacharam.core.MarkdownExport

private fun mb(bytes: Long) = if (bytes < 1_048_576) "%.0f KB".format(bytes / 1024.0) else "%.1f MB".format(bytes / 1_048_576.0)

private data class ProfileSummary(
    val articles: Long, val saved: Long, val sources: Int, val healthy: Int, val models: Int,
    val syncing: Boolean, val lastSync: Long?, val briefing: Boolean, val time: String, val tracking: Boolean, val bytes: Long,
)

@Composable
fun ProfileScreen(ctx: AppContext, nav: NavHostController) {
    val s by rememberLoaded(ctx) {
        val l = ctx.library; val stats = l.stats(); val sources = l.sources()
        ProfileSummary(
            stats.articles, stats.saved, sources.count { it.enabled }, sources.count { it.enabled && it.lastError == null && it.lastSuccess != null },
            l.aiSettings().providers.size, PhoneSync.enabled(l), l.meta("sync.lastSync")?.toLongOrNull(), l.briefingEnabled, l.briefingTime, l.tracking, stats.bytes,
        )
    }
    AppPage(ctx, "Profile") { padding ->
        val p = s ?: return@AppPage
        PageColumn(padding) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Your reading space", style = MaterialTheme.typography.titleLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("${p.articles}" to "items", "${p.saved}" to "saved", "${p.sources}" to "sources", "${p.models}" to "models").forEach { (n, label) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(n, style = MaterialTheme.typography.headlineSmall)
                                Text(label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (p.syncing) Icons.Outlined.CloudSync else Icons.Outlined.Storage, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (p.syncing) "Backed up to Google Drive" else "Stored only on this phone", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            SectionHeader("Reading")
            SettingsGroup {
                SettingRow("Sources", Icons.Outlined.RssFeed, "${p.healthy} of ${p.sources} healthy", onClick = { nav.navigate(Routes.SOURCES) })
                SettingRow("Briefing & notifications", Icons.Outlined.Notifications, if (p.briefing) "Daily at ${p.time}" else "Reminder off", onClick = { nav.navigate(Routes.BRIEFING) })
            }
            SectionHeader("Intelligence")
            SettingsGroup {
                SettingRow("Model connections", Icons.Outlined.SmartToy, if (p.models == 0) "Built-in rules only" else "${p.models} model${if (p.models == 1) "" else "s"} connected", onClick = { nav.navigate(Routes.MODELS) })
            }
            SectionHeader("Data")
            SettingsGroup {
                SettingRow("Backup & sync", Icons.Outlined.CloudSync, if (p.syncing) "Google Drive · synced ${p.lastSync?.let(::timeAgo) ?: "not yet"}" else "This phone only", onClick = { nav.navigate(Routes.SYNC) })
                SettingRow("Storage", Icons.Outlined.Storage, "${mb(p.bytes)} · ${p.articles} items", onClick = { nav.navigate(Routes.STORAGE) })
                SettingRow("Import & export", Icons.Outlined.ImportExport, "Markdown notes, data from the web app", onClick = { nav.navigate(Routes.DATA) })
            }
            SectionHeader("App")
            SettingsGroup {
                SettingRow("Privacy", Icons.Outlined.Shield, if (p.tracking) "History on" else "History paused", onClick = { nav.navigate(Routes.PRIVACY) })
                SettingRow("Appearance", Icons.Outlined.Palette, "Theme and colors", onClick = { nav.navigate(Routes.APPEARANCE) })
                SettingRow("About", Icons.Outlined.Info, "Version, principles, open-source licenses", onClick = { nav.navigate(Routes.ABOUT) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BriefingSettingsScreen(ctx: AppContext, nav: NavHostController) {
    val state by rememberLoaded(ctx) { Triple(ctx.library.briefingEnabled, ctx.library.briefingTime, ctx.library.meta("background.enabled") == "1") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) ctx.message = "Notifications are blocked. Allow them in Android settings to get the reminder."
    }
    var picking by remember { mutableStateOf(false) }
    AppPage(ctx, "Briefing", nav = nav) { padding ->
        val (enabled, time, background) = state ?: return@AppPage
        PageColumn(padding) {
            SectionHeader("Daily reminder")
            SettingsGroup {
                SwitchRow("Briefing notification", enabled, Icons.Outlined.Notifications, "Once a day, after the time you choose") { on ->
                    if (on && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    ctx.quick {
                        ctx.library.briefingEnabled = on
                        if (on) { ctx.library.setMeta("background.enabled", "1"); PhoneLibrary.enableBackgroundRefresh(ctx.activity) }
                        null
                    }
                }
                SettingRow("Time", Icons.Outlined.Schedule, time, onClick = { picking = true })
            }
            SectionHeader("Refresh")
            SettingsGroup {
                SwitchRow("Refresh in the background", background, Icons.Outlined.Sync, "Hourly while online and not low on battery") { on ->
                    ctx.quick {
                        ctx.library.setMeta("background.enabled", if (on) "1" else "0")
                        if (on) PhoneLibrary.enableBackgroundRefresh(ctx.activity) else PhoneLibrary.disableBackgroundRefresh(ctx.activity)
                        null
                    }
                }
            }
            Text("Android decides the exact moment background work runs, so the reminder can arrive up to about an hour after the chosen time. It is sent at most once per day.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        if (picking) {
            val picker = rememberTimePickerState(time.substringBefore(':').toInt(), time.substringAfter(':').toInt(), is24Hour = true)
            AlertDialog(
                onDismissRequest = { picking = false },
                title = { Text("Briefing time") },
                text = { TimePicker(picker) },
                confirmButton = {
                    TextButton(onClick = {
                        picking = false
                        val t = "%02d:%02d".format(picker.hour, picker.minute)
                        ctx.quick { ctx.library.briefingTime = t; "Briefing time set to $t" }
                    }) { Text("Set") }
                },
                dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
fun PrivacyScreen(ctx: AppContext, nav: NavHostController) {
    val tracking by rememberLoaded(ctx) { ctx.library.tracking }
    val stats by rememberLoaded(ctx) { ctx.library.stats() }
    var confirm by remember { mutableStateOf(false) }
    AppPage(ctx, "Privacy", nav = nav) { padding ->
        PageColumn(padding) {
            SectionHeader("History")
            SettingsGroup {
                SwitchRow("Record reading history", tracking == true, Icons.Outlined.History, "Opens, summaries, marked-read, and feedback. Used only to personalize ordering.") { on ->
                    ctx.quick { ctx.library.tracking = on; if (on) "History on" else "History paused — nothing new is recorded" }
                }
                SettingRow("Clear history and feedback", Icons.Outlined.DeleteForever, "${stats?.history ?: 0} events · ${stats?.feedback ?: 0} feedback. Saved items stay.", onClick = { confirm = true })
            }
            SectionHeader("What stays where")
            SettingsGroup {
                SettingRow("On this phone", Icons.Outlined.Shield, "Your library, notes, history, and model settings. API keys are in the Android Keystore and are never exported or synced.")
                SettingRow("Cloud models", Icons.Outlined.SmartToy, "Text is sent only to models you connected and allowed, and never while Local only is on.", onClick = { nav.navigate(Routes.MODELS) })
                SettingRow("Google Drive", Icons.Outlined.Backup, "Only if you turn on sync — encrypted on this phone with your passphrase.", onClick = { nav.navigate(Routes.SYNC) })
                SettingRow("Publishers", Icons.Outlined.Policy, "Feeds are fetched directly from publishers. Opening an original page is recorded only as “original opened”.")
            }
        }
    }
    if (confirm) ConfirmDialog("Clear history and feedback?", "Saved items and notes stay. This cannot be undone.", "Clear", destructive = true,
        onDismiss = { confirm = false }) { ctx.quick { ctx.library.clearHistory(); "History and feedback cleared" } }
}

@Composable
fun StorageScreen(ctx: AppContext, nav: NavHostController) {
    val stats by rememberLoaded(ctx) { ctx.library.stats() }
    val models by rememberLoaded(ctx) { ctx.device.root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    AppPage(ctx, "Storage", nav = nav) { padding ->
        val s = stats ?: return@AppPage
        PageColumn(padding) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(mb(s.bytes + (models ?: 0)), style = MaterialTheme.typography.displaySmall)
                    Text("used by samacha·RAM on this phone", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SectionHeader("Breakdown")
            SettingsGroup {
                SettingRow("Library database", Icons.Outlined.Storage, "${mb(s.bytes)} · ${s.articles} items (${s.trimmedArticles} older ones kept as title only)")
                SettingRow("Saved and annotated", Icons.Outlined.Backup, "${s.saved} saved · ${s.highlights} highlights — never cleaned up")
                SettingRow("Excerpt text", Icons.Outlined.ImportExport, "${mb(s.excerptBytes)} compressed")
                SettingRow("On-device models", Icons.Outlined.SmartToy, mb(models ?: 0), onClick = { nav.navigate(Routes.MODELS) })
            }
            SectionHeader("Cleanup")
            SettingsGroup {
                SettingRow("Clean up now", Icons.Outlined.CleaningServices, "Unsaved items keep their excerpt for 30 days and are removed after 90", onClick = {
                    ctx.run {
                        val r = ctx.library.applyRetention(System.currentTimeMillis()); ctx.library.compact()
                        "Cleaned up: ${r.excerptsDropped} excerpts trimmed, ${r.articlesDeleted} old items removed"
                    }
                })
            }
        }
    }
}

@Composable
fun AppearanceScreen(ctx: AppContext, nav: NavHostController) {
    val prefs by rememberLoaded(ctx) { (ThemeMode.entries.firstOrNull { it.name == ctx.library.meta("ui.theme") } ?: ThemeMode.System) to (ctx.library.meta("ui.dynamic") == "1") }
    AppPage(ctx, "Appearance", nav = nav) { padding ->
        val (mode, dynamic) = prefs ?: return@AppPage
        PageColumn(padding) {
            SectionHeader("Theme")
            SettingsGroup {
                ThemeMode.entries.forEach { m ->
                    SettingRow(m.label, if (m == ThemeMode.Dark) Icons.Outlined.DarkMode else Icons.Outlined.Palette, onClick = { ctx.quick { ctx.library.setMeta("ui.theme", m.name); null } }) {
                        RadioButton(mode == m, { ctx.quick { ctx.library.setMeta("ui.theme", m.name); null } })
                    }
                }
            }
            SectionHeader("Color")
            SettingsGroup {
                SwitchRow("Use wallpaper colors", dynamic, Icons.Outlined.Wallpaper,
                    if (Build.VERSION.SDK_INT >= 31) "Match your phone's Material You palette" else "Needs Android 12 or later", enabled = Build.VERSION.SDK_INT >= 31) { on ->
                    ctx.quick { ctx.library.setMeta("ui.dynamic", if (on) "1" else "0"); null }
                }
            }
        }
    }
}

@Composable
fun DataScreen(ctx: AppContext, nav: NavHostController) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ctx.run {
            val bytes = ctx.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            require(bytes.size <= 50 * 1024 * 1024) { "Export files over 50 MB are not supported." }
            val r = HostImport.import(bytes.decodeToString(), ctx.library, System.currentTimeMillis())
            "Imported ${r.articles} items, ${r.saved} saved, ${r.highlights} highlights, ${r.sources} sources" + if (r.skipped > 0) " (${r.skipped} skipped)" else ""
        }
    }
    AppPage(ctx, "Import & export", nav = nav) { padding ->
        PageColumn(padding) {
            SectionHeader("Export")
            SettingsGroup {
                SettingRow("Notes as Markdown", Icons.Outlined.ImportExport, "Saved items, notes, and highlights — readable in any notes app", onClick = {
                    ctx.run {
                        val files = MarkdownExport.export(ctx.library.savedItems())
                        if (files.isEmpty()) return@run "Save something first."
                        val text = files.entries.joinToString("\n\n---\n\n") { (path, body) -> "<!-- $path -->\n$body" }
                        ctx.activity.runOnUiThread { ctx.shareText("samacha·RAM notes", text) }
                        null
                    }
                })
            }
            SectionHeader("Import")
            SettingsGroup {
                SettingRow("From the samacha·RAM web app", Icons.Outlined.FileUpload, "Use Export in the web app, copy samacha-library.json to this phone, then pick it here",
                    onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) })
            }
        }
    }
}

@Composable
fun AboutScreen(ctx: AppContext, nav: NavHostController) {
    val version = remember { runCatching { ctx.activity.packageManager.getPackageInfo(ctx.activity.packageName, 0).versionName }.getOrNull() ?: "?" }
    AppPage(ctx, "About", nav = nav) { padding ->
        PageColumn(padding) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("samacha·RAM", style = MaterialTheme.typography.headlineMedium)
                Text("Version $version", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Your interests. Your sources. Your models.", style = MaterialTheme.typography.titleMedium)
            }
            SectionHeader("Principles")
            SettingsGroup {
                SettingRow("Honest labels", Icons.Outlined.Policy, "Nothing is marked verified automatically. Excerpts, summaries, and full text are labeled for what they are.")
                SettingRow("You decide the order", Icons.Outlined.Info, "Your layers rank the feed. Feedback and models only reorder within a layer.")
                SettingRow("Private by default", Icons.Outlined.Shield, "No account, no tracking servers. Cloud features are opt-in.")
            }
            SectionHeader("Open source")
            SettingsGroup {
                SettingRow("Built with", Icons.Outlined.Info, "Kotlin, Jetpack Compose, Material 3, SQLite, OkHttp, ONNX Runtime, LiteRT-LM, kotlinx.serialization")
            }
        }
    }
}

