package com.samacharam.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.work.WorkManager

class MainActivity : ComponentActivity() {
    private lateinit var ctx: AppContext

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ctx = AppContext(this)
        android.util.Log.i("samacharam", "activity created, restored=${savedInstanceState != null}")
        // Earlier versions scheduled a briefing that called the computer host; the phone library replaces it.
        WorkManager.getInstance(this).cancelUniqueWork("daily-briefing")
        if (savedInstanceState == null) handleShare(intent)
        setContent { SamachaApp(ctx) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Share to samacha·RAM" from a browser or another app saves the link; nothing is fetched. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'') ?: run {
            ctx.message = "The shared text has no link to save."
            return
        }
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: text.replace(url, "").trim().take(200).ifBlank { null }
        ctx.run { ctx.library.clip(url, title, System.currentTimeMillis()); "Saved to your library." }
    }
}
