package com.samacharam.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.samacharam.core.sync.SyncEngine
import java.text.DateFormat
import java.util.Date

private data class SyncInfo(val enabled: Boolean, val lastSync: Long?, val error: String?)

private enum class SyncDialog { Create, Unlock, Stop }

@Composable
fun SyncSection(ctx: AppContext) {
    val info by rememberLoaded(ctx) {
        val l = ctx.library
        SyncInfo(PhoneSync.enabled(l), l.meta("sync.lastSync")?.toLongOrNull(), l.meta("sync.error")?.ifBlank { null })
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
        GoogleDrive.authorize(
            ctx.activity,
            needsUi = { r -> r.pendingIntent?.let { consent.launch(IntentSenderRequest.Builder(it.intentSender).build()) } },
            onToken = action,
            onError = { ctx.message = it },
        )
    }

    SectionTitle("Backup and sync")
    val i = info ?: return
    if (!i.enabled) {
        Text("Your data is stored only on this phone.")
        Hint("Optionally back it up to Google Drive and sync between phones. Files go to a hidden app folder in your Drive and are encrypted on this phone with your passphrase, so Google cannot read them. Feed items, reading history, and API keys are not uploaded.")
        Button(onClick = {
            withToken { t ->
                token = t
                ctx.run { dialog = if (PhoneSync.engine(ctx.activity, t).backupExists()) SyncDialog.Unlock else SyncDialog.Create; null }
            }
        }, enabled = !ctx.busy) { Text("Back up and sync with Google Drive") }
    } else {
        Text("Syncing with Google Drive (encrypted, hidden app folder).")
        Hint("Last sync: " + (i.lastSync?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "not yet") + ". Background sync runs with the hourly refresh.")
        i.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = { withToken { t -> ctx.run { PhoneSync.summary(PhoneSync.syncNow(ctx.activity, t)) } } }, enabled = !ctx.busy) { Text("Sync now") }
        OutlinedButton(onClick = { dialog = SyncDialog.Stop }) { Text("Stop syncing") }
    }

    when (dialog) {
        SyncDialog.Create, SyncDialog.Unlock -> {
            val creating = dialog == SyncDialog.Create
            var pass by remember { mutableStateOf("") }
            var again by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(if (creating) "Create a backup passphrase" else "Enter your backup passphrase") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (creating) "Your backup is encrypted with this passphrase. You need it to restore on a new phone. If you lose it, the backup cannot be recovered — not by you, Google, or anyone."
                            else "A backup already exists in this Google account. Enter the passphrase you chose when you created it.",
                        )
                        OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase (at least ${SyncEngine.MIN_PASSPHRASE} characters)") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (creating) OutlinedTextField(again, { again = it }, label = { Text("Repeat passphrase") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val t = token ?: return@TextButton
                        val p = pass.toCharArray()
                        dialog = null
                        ctx.run {
                            val engine = PhoneSync.engine(ctx.activity, t)
                            if (creating) engine.createBackup(p) else engine.unlock(p)
                            p.fill(' ')
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
        SyncDialog.Stop -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Stop syncing?") },
            text = { Text("Everything stays on this phone. You can keep the encrypted Drive copy (for other phones or later) or delete it from Google Drive.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    withToken { t ->
                        ctx.run {
                            PhoneSync.engine(ctx.activity, t).disconnect(deleteRemote = true)
                            ctx.library.setMeta("storage.mode", "phone")
                            "Sync stopped and the Drive copy was deleted."
                        }
                    }
                }) { Text("Stop and delete Drive copy") }
            },
            dismissButton = {
                TextButton(onClick = {
                    dialog = null
                    ctx.run {
                        PhoneSync.engine(ctx.activity, "").disconnect(deleteRemote = false)
                        ctx.library.setMeta("storage.mode", "phone")
                        "Sync stopped. The encrypted Drive copy was kept."
                    }
                }) { Text("Stop, keep Drive copy") }
            },
        )
        null -> Unit
    }
}
