package com.samacharam.app

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
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
    const val TODAY = "today"; const val EXPLORE = "explore"; const val LIBRARY = "library"; const val INTERESTS = "interests"; const val PROFILE = "profile"
    const val ARTICLE = "article/{id}"; fun article(id: Long) = "article/$id"
    const val SOURCES = "sources"; const val ADD_SOURCE = "sources/add"
    const val BRIEFING = "settings/briefing"; const val MODELS = "settings/models"; const val ADD_MODEL = "settings/models/add"; const val ROUTES = "settings/models/routes"
    const val SYNC = "settings/sync"; const val PRIVACY = "settings/privacy"; const val STORAGE = "settings/storage"; const val APPEARANCE = "settings/appearance"
    const val DATA = "settings/data"; const val ABOUT = "settings/about"; const val MISSIONS = "interests/missions"; const val ONBOARDING = "onboarding"
}

private data class TopLevel(val route: String, val label: String, val selected: ImageVector, val unselected: ImageVector)

private val TOP_LEVEL = listOf(
    TopLevel(Routes.TODAY, "Today", Icons.Filled.Today, Icons.Outlined.Today),
    TopLevel(Routes.EXPLORE, "Explore", Icons.Filled.Explore, Icons.Outlined.Explore),
    TopLevel(Routes.LIBRARY, "Library", Icons.Filled.Bookmarks, Icons.Outlined.Bookmarks),
    TopLevel(Routes.INTERESTS, "Interests", Icons.Filled.Tune, Icons.Outlined.Tune),
    TopLevel(Routes.PROFILE, "Profile", Icons.Filled.AccountCircle, Icons.Outlined.AccountCircle),
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
    val rule = com.samacharam.app.ui.colors.rule
    val navColors = androidx.compose.material3.NavigationBarItemDefaults.colors(
        selectedIconColor = com.samacharam.app.ui.colors.evergreen, selectedTextColor = com.samacharam.app.ui.colors.ink,
        indicatorColor = com.samacharam.app.ui.colors.evergreenSoft,
        unselectedIconColor = com.samacharam.app.ui.colors.inkMuted, unselectedTextColor = com.samacharam.app.ui.colors.inkMuted,
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(showBar, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                NavigationBar(containerColor = com.samacharam.app.ui.colors.paper, tonalElevation = 0.dp,
                    modifier = Modifier.drawBehind { drawLine(rule, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }) {
                    TOP_LEVEL.forEach { item ->
                        val selected = route == item.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) nav.navigate(item.route) {
                                    popUpTo(Routes.TODAY) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(if (selected) item.selected else item.unselected, contentDescription = null) },
                            label = { Text(item.label, style = com.samacharam.app.ui.TypeRole.meta) },
                            colors = navColors,
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            nav, startDestination = if (startOnboarding) Routes.ONBOARDING else Routes.TODAY,
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
            composable(Routes.ONBOARDING) { OnboardingScreen(ctx) { nav.navigate(Routes.TODAY) { popUpTo(Routes.ONBOARDING) { inclusive = true } } } }
            composable(Routes.TODAY) { TodayScreen(ctx, nav) }
            composable(Routes.EXPLORE) { ExploreScreen(ctx, nav) }
            composable(Routes.LIBRARY) { LibraryScreen(ctx, nav) }
            composable(Routes.INTERESTS) { InterestsScreen(ctx, nav) }
            composable(Routes.PROFILE) { ProfileScreen(ctx, nav) }
            composable(Routes.ARTICLE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                ArticleScreen(ctx, nav, e.arguments?.getLong("id") ?: 0L)
            }
            composable(Routes.SOURCES) { SourcesScreen(ctx, nav) }
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

/**
 * Top-level tab: the masthead scrolls away with the list and a compact bar fades in once it has.
 */
@Composable
fun TabPage(
    ctx: AppContext,
    kicker: String,
    title: String,
    status: String? = null,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    floating: @Composable () -> Unit = {},
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val collapsed by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    Scaffold(containerColor = com.samacharam.app.ui.colors.paper, contentWindowInsets = WindowInsets(0), floatingActionButton = floating) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            androidx.compose.foundation.lazy.LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                item(key = "masthead") { com.samacharam.app.ui.Masthead(kicker, title, status, actions) }
                content()
            }
            Column {
                AnimatedVisibility(collapsed, enter = fadeIn(), exit = fadeOut()) {
                    com.samacharam.app.ui.CompactBar(title, visible = true, onBack = null, actions = actions)
                }
                if (!collapsed) Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                BusyLine(ctx)
            }
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
