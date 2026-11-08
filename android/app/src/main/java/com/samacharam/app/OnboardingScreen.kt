package com.samacharam.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.samacharam.app.ui.SettingRow
import com.samacharam.core.Layer

private val SUGGESTED = listOf("LLMs", "NLP", "Software", "Hardware", "AI audio", "AI video", "Robotics", "Security", "Databases", "Climate tech")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(ctx: AppContext, done: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val current by rememberLoaded(ctx) { ctx.library.profile().topics }
    var core by remember(current) { mutableStateOf(current.orEmpty().filterValues { it == Layer.Core }.keys) }
    var near by remember(current) { mutableStateOf(current.orEmpty().filterValues { it == Layer.Near }.keys) }

    fun finish() = ctx.quick {
        val all = (current.orEmpty().keys + SUGGESTED).toSet()
        for (topic in all) {
            val existing = current?.get(topic)
            val layer = when {
                topic in core -> Layer.Core
                topic in near -> Layer.Near
                existing == Layer.Core || existing == Layer.Near -> Layer.Discovery // deselected here
                existing != null -> existing
                else -> null // a suggestion the owner did not pick
            } ?: continue
            ctx.library.setLayer(topic, layer)
        }
        ctx.library.setMeta("ui.onboarded", "1")
        ctx.activity.runOnUiThread(done)
        null
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { i ->
                    Box(Modifier.height(4.dp).weight(1f).clip(CircleShape)
                        .background(if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
                }
            }
            Spacer(Modifier.height(32.dp))
            AnimatedContent(step, modifier = Modifier.weight(1f), label = "onboarding") { s ->
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (s) {
                        0 -> {
                            Hero(Icons.Outlined.AutoStories)
                            Text("Signal over noise.", style = MaterialTheme.typography.displaySmall)
                            Text("A calm, private reader for research papers, technical blogs, and news — organized around what you care about.", style = MaterialTheme.typography.bodyLarge)
                            SettingRow("Your interests decide", Icons.Outlined.Tune, "Rank by layers you control, never by engagement")
                            SettingRow("Your models, optional", Icons.Outlined.SmartToy, "Rules by default; connect on-device, local, or cloud models per task")
                            SettingRow("Private by default", Icons.Outlined.Shield, "No account. Everything stays on this phone unless you choose otherwise")
                        }
                        1 -> {
                            Hero(Icons.Outlined.Tune)
                            Text("What do you follow?", style = MaterialTheme.typography.headlineMedium)
                            Text("Core topics lead your feed. Tap once for Core, again for Near, a third time to clear. You can change this anytime in Interests.", style = MaterialTheme.typography.bodyMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                (current.orEmpty().keys + SUGGESTED).distinct().forEach { t ->
                                    val label = when (t) { in core -> "$t · Core"; in near -> "$t · Near"; else -> t }
                                    FilterChip(t in core || t in near, {
                                        when (t) { in core -> { core = core - t; near = near + t }; in near -> near = near - t; else -> core = core + t }
                                    }, { Text(label) })
                                }
                            }
                        }
                        else -> {
                            Hero(Icons.Outlined.PhoneAndroid)
                            Text("Your data, your choice", style = MaterialTheme.typography.headlineMedium)
                            SettingRow("On this phone", Icons.Outlined.PhoneAndroid, "The default. Works offline, no account needed.")
                            SettingRow("Google Drive sync, later", Icons.Outlined.CloudSync, "Encrypted with your passphrase. Turn it on anytime in Profile → Backup & sync.")
                            Text("Five public research feeds are ready to go. Pull down on Today to fetch the latest.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) TextButton(onClick = { step-- }) { Text("Back") } else TextButton(onClick = { finish() }) { Text("Skip") }
                Button(onClick = { if (step < 2) step++ else finish() }, enabled = step != 1 || core.isNotEmpty()) {
                    Text(when (step) { 0 -> "Get started"; 1 -> "Continue"; else -> "Start reading" })
                }
            }
        }
    }
}

@Composable
private fun Hero(icon: ImageVector) {
    Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
