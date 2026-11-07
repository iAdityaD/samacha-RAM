package com.samacharam.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.samacharam.core.ai.ModelRouter
import com.samacharam.core.db.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared app objects and a single place to run work off the main thread and report the outcome. */
class AppContext(val activity: ComponentActivity) {
    val library: Library by lazy { PhoneLibrary.get(activity) }
    val secrets by lazy { KeystoreSecrets(activity) }
    val router: ModelRouter by lazy { modelRouter(activity.applicationContext, library::aiSettings, library.cloudUsage) }
    val device by lazy { DeviceModels(activity) }

    /** Incremented after every change so screens reload their data. */
    var version by mutableIntStateOf(0)
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)

    fun run(block: suspend () -> String?) {
        if (busy) return
        busy = true
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block() }
                if (result != null) message = result
            } catch (e: Exception) {
                message = e.message ?: "Something went wrong."
            } finally {
                busy = false
                version++
            }
        }
    }
}

/** Loads data off the main thread; reloads when [AppContext.version] or any key changes. */
@Composable
fun <T> rememberLoaded(ctx: AppContext, vararg keys: Any?, load: () -> T): State<T?> {
    val state = remember { mutableStateOf<T?>(null) }
    val reloadKey: List<Any?> = listOf(ctx.version, *keys)
    LaunchedEffect(reloadKey) {
        state.value = withContext(Dispatchers.IO) {
            runCatching(load).onFailure { ctx.message = it.message ?: "Could not load data." }.getOrNull()
        }
    }
    return state
}

enum class Tab(val label: String) { Today("Today"), Library("Library"), Interests("Interests"), Sources("Sources"), Settings("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamachaApp(ctx: AppContext, dark: Boolean) {
    var tab by remember { mutableStateOf(Tab.Today) }
    var openArticle by remember { mutableStateOf<Long?>(null) }
    var showModels by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ctx.message) {
        ctx.message?.let { snackbar.showSnackbar(it); ctx.message = null }
    }
    val colors = if (dark) darkColorScheme(primary = Color(0xff9fcdb3)) else lightColorScheme(primary = Color(0xff315645))
    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = { TopAppBar(title = { Text(if (showModels) "Model connections" else "samacha·RAM") }) },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t && !showModels,
                            onClick = { tab = t; showModels = false },
                            icon = { Text(t.label.take(1), style = MaterialTheme.typography.titleMedium) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (ctx.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when {
                    showModels -> ModelsScreen(ctx, onBack = { showModels = false })
                    tab == Tab.Today -> TodayScreen(ctx) { openArticle = it }
                    tab == Tab.Library -> LibraryScreen(ctx) { openArticle = it }
                    tab == Tab.Interests -> InterestsScreen(ctx)
                    tab == Tab.Sources -> SourcesScreen(ctx)
                    tab == Tab.Settings -> SettingsScreen(ctx, onModels = { showModels = true })
                }
            }
        }
        openArticle?.let { id -> ArticleDialog(ctx, id) { openArticle = null } }
    }
}

/** Scrollable screen body with consistent spacing. */
@Composable
fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleLarge)

@Composable
fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
