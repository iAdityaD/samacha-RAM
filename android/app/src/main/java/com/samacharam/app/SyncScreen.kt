package com.samacharam.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncDisabled
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.samacharam.app.ui.ConfirmDialog
import com.samacharam.app.ui.SectionHeader
import com.samacharam.app.ui.SettingRow
import com.samacharam.app.ui.SettingsGroup
import com.samacharam.app.ui.timeAgo
import com.samacharam.core.sync.SyncEngine

private enum class SyncDialog { Create, Unlock, StopKeep, StopDelete }

@Composable
fun SyncScreen(ctx: AppContext, nav: NavHostController) {
    val info by rememberLoaded(ctx) {
        val l = ctx.library
        Triple(PhoneSync.enabled(l), l.meta("sync.lastSync")?.toLongOrNull(), l.meta("sync.error")?.ifBlank { null })
    }
    var token by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<SyncDialog?>(null) }
    var afterToken by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val t = GoogleDrive.tokenFromConsent(ctx.activity, result.data)
        if (t == null) ctx.message = "Google Drive access was not granted." else afterToken?.invoke(t)
    }
    fun withToken(action: (String) -> Unit) {
        afterToken = action
        GoogleDrive.authorize(ctx.activity,
            needsUi = { r -> r.pendingIntent?.let { consent.launch(IntentSenderRequest.Builder(it.intentSender).build()) } },
            onToken = action, onError = { ctx.message = it })
    }

    AppPage(ctx, "Backup & sync", nav = nav) { padding ->
        val (enabled, lastSync, error) = info ?: return@AppPage
        PageColumn(padding) {
            Card(
                colors = CardDefaults.cardColors(containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (enabled) Icons.Outlined.CloudDone else Icons.Outlined.PhoneAndroid, null)
                    Text(if (enabled) "Syncing with Google Drive" else "Stored only on this phone", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (enabled) "Last sync ${lastSync?.let(::timeAgo) ?: "not yet"}. Sync also runs with the hourly background refresh."
                        else "Back up to a hidden app folder in your Google Drive and keep several phones in sync.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!enabled) Button(onClick = {
                        withToken { t -> token = t; ctx.run { dialog = if (PhoneSync.engine(ctx.activity, t).backupExists()) SyncDialog.Unlock else SyncDialog.Create; null } }
                    }, enabled = !ctx.busy) { Text("Set up Google Drive sync") }
                }
            }
            error?.takeIf { enabled }?.let {
                SettingsGroup { SettingRow("Last sync problem", Icons.Outlined.ErrorOutline, it) }
            }
            if (enabled) {
                SectionHeader("Actions")
                SettingsGroup {
                    SettingRow("Sync now", Icons.Outlined.Sync, "Upload changes and merge other phones", onClick = {
                        withToken { t -> ctx.run { PhoneSync.summary(PhoneSync.syncNow(ctx.activity, t)) } }
                    })
                    SettingRow("Stop syncing", Icons.Outlined.SyncDisabled, "Keep the encrypted Drive copy for other phones", onClick = { dialog = SyncDialog.StopKeep })
                    SettingRow("Stop and delete Drive copy", Icons.Outlined.DeleteForever, "Removes the backup from Google Drive", onClick = { dialog = SyncDialog.StopDelete })
                }
            }
            SectionHeader("How it works")
            SettingsGroup {
                SettingRow("End-to-end encrypted", Icons.Outlined.Lock, "Encrypted on this phone with AES-256 using your passphrase. Google stores data it cannot read.")
                SettingRow("What syncs", Icons.Outlined.Sync, "Interests, sources, saved items with notes, highlights, missions")
                SettingRow("What never leaves this phone", Icons.Outlined.CloudOff, "Reading history, API keys, model files, feed items (each phone fetches its own)")
            }
        }
    }

    when (val d = dialog) {
        SyncDialog.Create, SyncDialog.Unlock -> {
            val creating = d == SyncDialog.Create
            var pass by remember { mutableStateOf("") }
            var again by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                icon = { Icon(Icons.Outlined.Lock, null) },
                title = { Text(if (creating) "Create a backup passphrase" else "Enter your backup passphrase") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (creating) "You need this passphrase to restore on another phone. If you lose it, the backup cannot be recovered — not by you, Google, or anyone."
                        else "A backup already exists in this Google account. Enter the passphrase you chose when you created it.")
                        OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase (${SyncEngine.MIN_PASSPHRASE}+ characters)") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (creating) OutlinedTextField(again, { again = it }, label = { Text("Repeat passphrase") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
                            isError = again.isNotEmpty() && again != pass)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val t = token ?: return@TextButton
                        val p = pass.toCharArray()
                        dialog = null
                        ctx.run {
                            val engine = PhoneSync.engine(ctx.activity, t)
                            try { if (creating) engine.createBackup(p) else engine.unlock(p) } finally { p.fill(' ') }
                            ctx.library.setMeta("storage.mode", "drive")
                            ctx.library.setMeta("background.enabled", "1")
                            PhoneLibrary.enableBackgroundRefresh(ctx.activity)
                            PhoneSync.summary(PhoneSync.syncNow(ctx.activity, t))
                        }
                    }, enabled = pass.length >= SyncEngine.MIN_PASSPHRASE && (!creating || pass == again)) { Text(if (creating) "Create backup" else "Unlock") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        SyncDialog.StopKeep -> ConfirmDialog("Stop syncing?", "Everything stays on this phone and the encrypted Drive copy is kept.", "Stop syncing",
            onDismiss = { dialog = null }) {
            ctx.run { PhoneSync.engine(ctx.activity, "").disconnect(deleteRemote = false); ctx.library.setMeta("storage.mode", "phone"); "Sync stopped. The Drive copy was kept." }
        }
        SyncDialog.StopDelete -> ConfirmDialog("Delete the Drive copy?", "Everything stays on this phone. The backup is removed from Google Drive for all phones.", "Delete", destructive = true,
            onDismiss = { dialog = null }) {
            withToken { t -> ctx.run { PhoneSync.engine(ctx.activity, t).disconnect(deleteRemote = true); ctx.library.setMeta("storage.mode", "phone"); "Sync stopped and the Drive copy was deleted." } }
        }
        null -> Unit
    }
}
