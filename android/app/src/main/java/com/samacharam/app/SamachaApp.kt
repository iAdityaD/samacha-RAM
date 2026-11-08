package com.samacharam.app

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.samacharam.app.ui.Motion
import com.samacharam.app.ui.SamachaTheme
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import com.samacharam.app.ui.ThemeMode
import com.samacharam.core.Article
import com.samacharam.core.FeedbackReason
import com.samacharam.core.ReadingEvent
import com.samacharam.core.ai.ModelRouter
import com.samacharam.core.db.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared app objects and one place to run work off the main thread and report the outcome. */
class AppContext(val activity: ComponentActivity) {
    val library: Library by lazy { PhoneLibrary.get(activity) }
    val secrets by lazy { KeystoreSecrets(activity) }
    val router: ModelRouter by lazy { modelRouter(activity.applicationContext, library::aiSettings, library.cloudUsage) }
    val device by lazy { DeviceModels(activity) }

    /** Incremented after every change so screens reload their data. */
    var version by mutableIntStateOf(0)
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)

    /** Ask conversations for this session, keyed by story id (-1 is the whole library). */
    val askThreads = androidx.compose.runtime.mutableStateMapOf<Long, List<AskMessage>>()
    /** Which Profile tab is showing (0 topics, 1 sources); other screens can send the owner straight to sources. */
    var profileTab by mutableIntStateOf(0)

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

    /** Quick actions that do not block the UI with the busy indicator. */
    fun quick(block: () -> String?) {
        activity.lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { block() }?.let { message = it } } catch (e: Exception) { message = e.message } finally { version++ }
        }
    }

    fun toggleSave(id: Long) = quick { if (library.isSaved(id)) { library.unsave(id); "Removed from Library" } else { library.save(id, System.currentTimeMillis()); "Saved to Library" } }
    fun markRead(id: Long) = quick { if (library.recordEvent(id, ReadingEvent.MarkRead, System.currentTimeMillis())) "Marked as read" else "History is paused; nothing recorded" }
    fun feedback(id: Long, reason: FeedbackReason) = quick {
        if (library.addFeedback(id, reason, System.currentTimeMillis())) "Thanks — this reorders items within your layers" else "History is paused; feedback not stored"
    }
    fun openOriginal(article: Article) {
        quick { library.recordEvent(article.id, ReadingEvent.OriginalOpen, System.currentTimeMillis()); null }
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.url))) }.onFailure { message = "No app can open this link." }
    }
    fun share(article: Article) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, article.title).putExtra(Intent.EXTRA_TEXT, "${article.title}\n${article.url}")
        activity.startActivity(Intent.createChooser(send, "Share"))
    }
    fun shareText(subject: String, text: String) =
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text), subject))
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

object Routes {
    const val HOME = "home"; const val EXPLORE = "explore"; const val ASK = "ask?article={article}"; const val LIBRARY = "library"; const val PROFILE = "profile"
    fun ask(articleId: Long = -1L) = "ask?article=$articleId"
    const val ARTICLE = "article/{id}"; fun article(id: Long) = "article/$id"
    const val STORY = "story/{deck}"; fun story(deck: String) = "story/" + Uri.encode(deck)
    const val TOPIC = "topic/{name}"; fun topic(name: String) = "topic/" + Uri.encode(name)
    const val ACTIVITY = "activity"; const val SETTINGS = "settings"; const val ADD_SOURCE = "sources/add"; const val MISSIONS = "missions"
    const val BRIEFING = "settings/briefing"; const val MODELS = "settings/models"; const val ADD_MODEL = "settings/models/add"; const val ROUTES = "settings/models/routes"
    const val SYNC = "settings/sync"; const val PRIVACY = "settings/privacy"; const val STORAGE = "settings/storage"; const val APPEARANCE = "settings/appearance"
    const val DATA = "settings/data"; const val ABOUT = "settings/about"; const val ONBOARDING = "onboarding"
}

private data class TopLevel(val route: String, val target: String, val label: String, val selected: ImageVector, val unselected: ImageVector)

/** Five tabs, icon only (each has a spoken label). Profile shows your initial instead of an icon. */
private val TOP_LEVEL = listOf(
    TopLevel(Routes.HOME, Routes.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    TopLevel(Routes.EXPLORE, Routes.EXPLORE, "Explore", Icons.Filled.Search, Icons.Outlined.Search),
    TopLevel(Routes.ASK, Routes.ask(), "Ask", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    TopLevel(Routes.LIBRARY, Routes.LIBRARY, "Library", Icons.Filled.Bookmarks, Icons.Outlined.Bookmarks),
    TopLevel(Routes.PROFILE, Routes.PROFILE, "Profile", Icons.Filled.AccountCircle, Icons.Outlined.AccountCircle),
)

@Composable
fun SamachaApp(ctx: AppContext) {
    val prefs by rememberLoaded(ctx) {
        Triple(
            ThemeMode.entries.firstOrNull { it.name == ctx.library.meta("ui.theme") } ?: ThemeMode.System,
            ctx.library.meta("ui.dynamic") == "1",
            ctx.library.meta("ui.onboarded") == "1",
        )
    }
    val p = prefs
    SamachaTheme(p?.first ?: ThemeMode.System, p?.second ?: false) {
        if (p != null) AppNavigation(ctx, startOnboarding = !p.third)
    }
}

@Composable
private fun AppNavigation(ctx: AppContext, startOnboarding: Boolean) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ctx.message) { ctx.message?.let { snackbar.showSnackbar(it); ctx.message = null } }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = TOP_LEVEL.any { it.route == route }
    val name by rememberLoaded(ctx) { ctx.library.meta("ui.name")?.ifBlank { null } ?: "Reader" }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(showBar, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                val c = com.samacharam.app.ui.colors
                Column(Modifier.background(c.paper).navigationBarsPadding()) {
                    androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = c.rule)
                    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        TOP_LEVEL.forEach { item ->
                            val selected = route == item.route
                            Box(
                                Modifier.weight(1f).fillMaxHeight().selectable(selected, role = Role.Tab) {
                                    if (!selected) nav.navigate(item.target) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }.semantics { contentDescription = item.label },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (item.route == Routes.PROFILE) Box(
                                    Modifier.size(28.dp).then(if (selected) Modifier.border(1.5.dp, c.ink, CircleShape).padding(2.5.dp) else Modifier),
                                    contentAlignment = Alignment.Center,
                                ) { com.samacharam.app.ui.Avatar(name ?: "Reader", if (selected) 23.dp else 26.dp, filled = true) }
                                else Icon(if (selected) item.selected else item.unselected, null, tint = if (selected) c.ink else c.inkMuted, modifier = Modifier.size(26.dp))
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            nav, startDestination = if (startOnboarding) Routes.ONBOARDING else Routes.HOME,
            modifier = Modifier.padding(padding),
            // Tabs crossfade; detail pages rise 24 dp and fade in (Motion tokens).
            enterTransition = {
                val tabs = TOP_LEVEL.map { it.route }
                if (initialState.destination.route in tabs && targetState.destination.route in tabs) fadeIn(tween(Motion.STANDARD))
                else slideInVertically(tween(Motion.STANDARD, easing = Motion.EmphasizedDecelerate)) { 72 } + fadeIn(tween(Motion.STANDARD))
            },
            exitTransition = { fadeOut(tween(Motion.EXIT)) },
            popEnterTransition = { fadeIn(tween(Motion.STANDARD)) },
            popExitTransition = { slideOutVertically(tween(Motion.EXIT, easing = Motion.EmphasizedAccelerate)) { 72 } + fadeOut(tween(Motion.EXIT)) },
        ) {
            composable(Routes.ONBOARDING) { OnboardingScreen(ctx) { nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } } }
            composable(Routes.HOME) { HomeScreen(ctx, nav) }
            composable(Routes.EXPLORE) { ExploreScreen(ctx, nav) }
            composable(Routes.ASK, arguments = listOf(navArgument("article") { type = NavType.LongType; defaultValue = -1L })) { e ->
                AskScreen(ctx, nav, e.arguments?.getLong("article") ?: -1L)
            }
            composable(Routes.LIBRARY) { LibraryScreen(ctx, nav) }
            composable(Routes.PROFILE) { ProfileScreen(ctx, nav) }
            composable(Routes.STORY) { e -> StoryPlayerScreen(ctx, nav, e.arguments?.getString("deck") ?: Decks.BRIEFING) }
            composable(Routes.TOPIC) { e -> TopicScreen(ctx, nav, e.arguments?.getString("name") ?: "") }
            composable(Routes.ACTIVITY) { ActivityScreen(ctx, nav) }
            composable(Routes.SETTINGS) { SettingsScreen(ctx, nav) }
            composable(Routes.ARTICLE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                ArticleScreen(ctx, nav, e.arguments?.getLong("id") ?: 0L)
            }
            composable(Routes.ADD_SOURCE) { AddSourceScreen(ctx, nav) }
            composable(Routes.MISSIONS) { MissionsScreen(ctx, nav) }
            composable(Routes.BRIEFING) { BriefingSettingsScreen(ctx, nav) }
            composable(Routes.MODELS) { ModelsScreen(ctx, nav) }
            composable(Routes.ADD_MODEL) { AddModelScreen(ctx, nav) }
            composable(Routes.ROUTES) { RoutesScreen(ctx, nav) }
            composable(Routes.SYNC) { SyncScreen(ctx, nav) }
            composable(Routes.PRIVACY) { PrivacyScreen(ctx, nav) }
            composable(Routes.STORAGE) { StorageScreen(ctx, nav) }
            composable(Routes.APPEARANCE) { AppearanceScreen(ctx, nav) }
            composable(Routes.DATA) { DataScreen(ctx, nav) }
            composable(Routes.ABOUT) { AboutScreen(ctx, nav) }
        }
    }
}

/**
 * Detail and settings page: a slim bar with Back and the title, then content.
 * ([large] is kept for call-site compatibility; all detail pages use the compact bar.)
 */
@Composable
fun AppPage(
    ctx: AppContext,
    title: String,
    nav: NavHostController? = null,
    @Suppress("UNUSED_PARAMETER") large: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    floating: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = com.samacharam.app.ui.colors.paper,
        contentWindowInsets = if (nav != null) WindowInsets.navigationBars else WindowInsets(0),
        floatingActionButton = floating,
        topBar = {
            Column {
                com.samacharam.app.ui.CompactBar(title, visible = true, onBack = nav?.let { n -> { n.popBackStack() } }, actions = actions)
                BusyLine(ctx)
            }
        },
    ) { padding -> content(padding) }
}

@Composable
fun BusyLine(ctx: AppContext) {
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        AnimatedVisibility(ctx.busy, enter = fadeIn(), exit = fadeOut()) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = com.samacharam.app.ui.colors.evergreen, trackColor = com.samacharam.app.ui.colors.rule)
        }
    }
}

/** Scrollable column body for forms and settings pages. */
@Composable
fun PageColumn(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(top = com.samacharam.app.ui.Space.s, bottom = 96.dp), // clears floating buttons
        content = content,
    )
}
