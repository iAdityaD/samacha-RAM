package com.samacharam.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import com.samacharam.app.ui.ConfirmDialog
import com.samacharam.app.ui.Kicker
import com.samacharam.app.ui.SectionLabel
import com.samacharam.app.ui.SettingRow
import com.samacharam.app.ui.SettingsGroup
import com.samacharam.app.ui.Space
import com.samacharam.app.ui.SwitchRow
import com.samacharam.app.ui.ThemeMode
import com.samacharam.app.ui.TypeRole
import com.samacharam.app.ui.colors
import com.samacharam.app.ui.timeAgo
import com.samacharam.core.HostImport
import com.samacharam.core.MarkdownExport

private fun mb(bytes: Long) = if (bytes < 1_048_576) "%.0f KB".format(bytes / 1024.0) else "%.1f MB".format(bytes / 1_048_576.0)

private data class SettingsSummary(
    val models: Int, val syncing: Boolean, val lastSync: Long?, val briefing: Boolean, val time: String, val tracking: Boolean, val bytes: Long, val theme: ThemeMode,
)

@Composable
private fun Chevron() = Icon(Icons.Outlined.ChevronRight, null, tint = colors.inkFaint)

/** Settings and data: everything about how the app works. What you follow lives on Profile, not here. */
@Composable
fun SettingsScreen(ctx: AppContext, nav: NavHostController) {
    val s by rememberLoaded(ctx) {
        val l = ctx.library
        SettingsSummary(
            l.aiSettings().providers.size, PhoneSync.enabled(l), l.meta("sync.lastSync")?.toLongOrNull(), l.briefingEnabled, l.briefingTime, l.tracking, l.stats().bytes,
            ThemeMode.entries.firstOrNull { it.name == l.meta("ui.theme") } ?: ThemeMode.System,
        )
    }
    AppPage(ctx, "Settings and data", nav = nav) { padding ->
        val p = s ?: return@AppPage
        PageColumn(padding) {
            SectionLabel("Your briefing")
            SettingsGroup {
                SettingRow("Briefing and notifications", Icons.Outlined.Notifications, if (p.briefing) "Reminder daily at ${p.time}" else "Reminder off",
                    onClick = { nav.navigate(Routes.BRIEFING) }) { Chevron() }
            }
            SectionLabel("Intelligence")
            SettingsGroup {
                SettingRow("Model connections", Icons.Outlined.SmartToy,
                    if (p.models == 0) "Built-in rules. Connect a model per task." else "${p.models} model${if (p.models == 1) "" else "s"} connected",
                    onClick = { nav.navigate(Routes.MODELS) }) { Chevron() }
            }
            SectionLabel("Your data")
            SettingsGroup {
                SettingRow("Backup and sync", Icons.Outlined.CloudSync,
                    if (p.syncing) "Google Drive · synced ${p.lastSync?.let(::timeAgo) ?: "not yet"}" else "Only on this phone",
                    onClick = { nav.navigate(Routes.SYNC) }) { Chevron() }
                SettingRow("Storage", Icons.Outlined.Storage, "${mb(p.bytes)} on this phone", onClick = { nav.navigate(Routes.STORAGE) }) { Chevron() }
                SettingRow("Import and export", Icons.Outlined.ImportExport, "Markdown notes, web app data", onClick = { nav.navigate(Routes.DATA) }) { Chevron() }
                SettingRow("Privacy", Icons.Outlined.Shield, if (p.tracking) "Reading history on" else "Reading history paused", onClick = { nav.navigate(Routes.PRIVACY) }) { Chevron() }
            }
            SectionLabel("App")
            SettingsGroup {
                SettingRow("Appearance", Icons.Outlined.Palette, p.theme.label, onClick = { nav.navigate(Routes.APPEARANCE) }) { Chevron() }
                SettingRow("About samacha·RAM", Icons.Outlined.Info, onClick = { nav.navigate(Routes.ABOUT) }) { Chevron() }
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
            SectionLabel("Daily reminder")
            SettingsGroup {
                SwitchRow("Remind me to read", enabled, Icons.Outlined.Notifications, "One notification a day with your briefing") { on ->
                    if (on && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    ctx.quick {
                        ctx.library.briefingEnabled = on
                        if (on) { ctx.library.setMeta("background.enabled", "1"); PhoneLibrary.enableBackgroundRefresh(ctx.activity) }
                        null
                    }
                }
                SettingRow("Time", Icons.Outlined.Schedule, time, onClick = { picking = true }) { Chevron() }
            }
            SectionLabel("Refreshing")
            SettingsGroup {
                SwitchRow("Refresh in the background", background, Icons.Outlined.Sync, "About hourly, on any network, when the battery is not low") { on ->
                    ctx.quick {
                        ctx.library.setMeta("background.enabled", if (on) "1" else "0")
                        if (on) PhoneLibrary.enableBackgroundRefresh(ctx.activity) else PhoneLibrary.disableBackgroundRefresh(ctx.activity)
                        null
                    }
                }
            }
            Text("Android decides exactly when background work runs, so the reminder can arrive up to an hour after the chosen time. It is sent at most once a day.",
                style = TypeRole.meta.copy(fontWeight = TypeRole.ui.fontWeight), color = colors.inkMuted, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.l))
        }
        if (picking) {
            val picker = rememberTimePickerState(time.substringBefore(':').toInt(), time.substringAfter(':').toInt(), is24Hour = true)
            AlertDialog(
                onDismissRequest = { picking = false }, containerColor = colors.paperRaised,
                title = { Text("Briefing time", style = TypeRole.headline, color = colors.ink) },
                text = {
                    TimePicker(picker, colors = TimePickerDefaults.colors(
                        clockDialColor = colors.paperSunk, selectorColor = colors.evergreen, timeSelectorSelectedContainerColor = colors.evergreenSoft,
                        timeSelectorSelectedContentColor = colors.evergreen, timeSelectorUnselectedContainerColor = colors.paperSunk))
                },
                confirmButton = {
                    TextButton(onClick = {
                        picking = false
                        val t = "%02d:%02d".format(picker.hour, picker.minute)
                        ctx.quick { ctx.library.briefingTime = t; "Reminder set for $t" }
                    }) { Text("Set", style = TypeRole.title, color = colors.evergreen) }
                },
                dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel", style = TypeRole.title, color = colors.inkMuted) } },
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
            SectionLabel("Reading history")
            SettingsGroup {
                SwitchRow("Record reading history", tracking == true, Icons.Outlined.History, "Opens, summaries, and feedback. Used only to order items within your layers.") { on ->
                    ctx.quick { ctx.library.tracking = on; if (on) "Reading history on" else "Reading history paused" }
                }
                SettingRow("Clear history and feedback", Icons.Outlined.DeleteForever, "${stats?.history ?: 0} events · ${stats?.feedback ?: 0} feedback. Saved items stay.",
                    onClick = { confirm = true }, destructive = true)
            }
            SectionLabel("Where your data goes")
            SettingsGroup {
                SettingRow("This phone", Icons.Outlined.PhoneAndroid, "Library, notes, history, and settings. API keys stay in the Android Keystore and are never exported or synced.")
                SettingRow("Models you connect", Icons.Outlined.SmartToy, "Text goes only to models you add and allow, never while Local only is on.",
                    onClick = { nav.navigate(Routes.MODELS) }) { Chevron() }
                SettingRow("Google Drive", Icons.Outlined.Backup, "Only if you turn on sync, encrypted on this phone with your passphrase.",
                    onClick = { nav.navigate(Routes.SYNC) }) { Chevron() }
                SettingRow("Publishers", Icons.Outlined.Policy, "Feeds are fetched directly. Opening a story at the publisher is recorded only as that.")
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
        val st = stats ?: return@AppPage
        PageColumn(padding) {
            Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s)) {
                Kicker("Used on this phone")
                Text(mb(st.bytes + (models ?: 0)), style = TypeRole.display, color = colors.ink)
            }
            SectionLabel("Breakdown")
            SettingsGroup {
                SettingRow("Library", Icons.Outlined.Storage, "${mb(st.bytes)} · ${st.articles} stories, ${st.trimmedArticles} kept as title only")
                SettingRow("Saved and annotated", Icons.Outlined.Backup, "${st.saved} saved · ${st.highlights} highlights · never cleaned up")
                SettingRow("Excerpt text", Icons.Outlined.ImportExport, "${mb(st.excerptBytes)} after compression")
                SettingRow("On-device models", Icons.Outlined.SmartToy, mb(models ?: 0), onClick = { nav.navigate(Routes.MODELS) }) { Chevron() }
            }
            SectionLabel("Cleanup")
            SettingsGroup {
                SettingRow("Clean up now", Icons.Outlined.CleaningServices, "Unsaved stories keep their excerpt 30 days and are removed after 90. This runs automatically after each refresh.", onClick = {
                    ctx.run {
                        val r = ctx.library.applyRetention(System.currentTimeMillis()); ctx.library.compact()
                        if (r.excerptsDropped + r.articlesDeleted == 0) "Nothing to clean up" else "Trimmed ${r.excerptsDropped} excerpts, removed ${r.articlesDeleted} old stories"
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
            SectionLabel("Theme")
            SettingsGroup {
                ThemeMode.entries.forEach { m ->
                    val pick = { ctx.quick { ctx.library.setMeta("ui.theme", m.name); null } }
                    SettingRow(m.label, when (m) { ThemeMode.Dark -> Icons.Outlined.DarkMode; ThemeMode.Light -> Icons.Outlined.LightMode; else -> Icons.Outlined.PhoneAndroid }, onClick = pick) {
                        RadioButton(mode == m, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.evergreen))
                    }
                }
            }
            SectionLabel("Color")
            SettingsGroup {
                SwitchRow("Use wallpaper colors", dynamic, Icons.Outlined.Wallpaper,
                    if (Build.VERSION.SDK_INT >= 31) "Tint actions with your phone's palette. Paper and ink stay the same." else "Needs Android 12 or later",
                    enabled = Build.VERSION.SDK_INT >= 31) { on -> ctx.quick { ctx.library.setMeta("ui.dynamic", if (on) "1" else "0"); null } }
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
            "Imported ${r.articles} stories, ${r.saved} saved, ${r.highlights} highlights" + if (r.skipped > 0) " (${r.skipped} skipped)" else ""
        }
    }
    AppPage(ctx, "Import and export", nav = nav) { padding ->
        PageColumn(padding) {
            SectionLabel("Export")
            SettingsGroup {
                SettingRow("Notes as Markdown", Icons.Outlined.ImportExport, "Saved stories, notes, and highlights for any notes app", onClick = {
                    ctx.run {
                        val files = MarkdownExport.export(ctx.library.savedItems())
                        if (files.isEmpty()) return@run "Save something first."
                        val text = files.entries.joinToString("\n\n---\n\n") { (path, body) -> "<!-- $path -->\n$body" }
                        ctx.activity.runOnUiThread { ctx.shareText("samacha·RAM notes", text) }
                        null
                    }
                }) { Chevron() }
            }
            SectionLabel("Import")
            SettingsGroup {
                SettingRow("From the samacha·RAM web app", Icons.Outlined.FileUpload, "Export samacha-library.json in the web app, copy it to this phone, then choose it here",
                    onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Chevron() }
            }
        }
    }
}

@Composable
fun AboutScreen(ctx: AppContext, nav: NavHostController) {
    val version = remember { runCatching { ctx.activity.packageManager.getPackageInfo(ctx.activity.packageName, 0).versionName }.getOrNull() ?: "?" }
    AppPage(ctx, "About", nav = nav) { padding ->
        PageColumn(padding) {
            Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Kicker("Version $version")
                Text("samacha·RAM", style = TypeRole.display, color = colors.ink)
                Text("Your interests. Your sources. Your models.", style = TypeRole.body, color = colors.inkMuted)
            }
            SectionLabel("Principles")
            SettingsGroup {
                SettingRow("Honest labels", Icons.Outlined.Policy, "Nothing is marked verified automatically. Excerpts and summaries are labeled for what they are.")
                SettingRow("You decide the order", Icons.Outlined.Info, "Your layers rank the feed. Feedback and models only reorder within a layer.")
                SettingRow("Private by default", Icons.Outlined.Shield, "No account and no tracking servers. Cloud features are opt-in.")
            }
            SectionLabel("Built with")
            Text("Kotlin, Jetpack Compose, Material 3, SQLite, OkHttp, ONNX Runtime, LiteRT-LM, kotlinx.serialization",
                style = TypeRole.ui, color = colors.inkMuted, modifier = Modifier.padding(horizontal = Space.gutter))
        }
    }
}

