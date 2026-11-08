package com.samacharam.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.samacharam.core.ContentType
import com.samacharam.core.FeedItem
import com.samacharam.core.db.Library
import com.samacharam.core.db.UpsertResult
import com.samacharam.core.db.queryLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs on an emulator in CI: the bundled SQLite driver, live public feeds, and every screen. */
@RunWith(AndroidJUnit4::class)
class BundledDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun schemaFullTextSearchAndRetentionWorkOnAndroid() {
        val file = File(context.cacheDir, "driver-test.db").also { it.delete() }
        BundledSqlDriver(file).use { driver ->
            val library = Library(driver).also { it.open(); it.seedDefaults() }
            assertEquals("3", library.meta("schema_version"))
            assertEquals(2L, driver.queryLong("PRAGMA auto_vacuum"))
            val now = System.currentTimeMillis()
            val id = (library.upsertArticle(null, FeedItem("https://e.com/a", "Quantized speech encoders", "", now, "Streaming recognition on phones.", ContentType.Paper, listOf("AI audio")), now) as UpsertResult.Inserted).id
            assertEquals(listOf(id), library.search("speech phones"))
            library.save(id, now, "note ✓ दस्तावेज़")
            assertEquals("note ✓ दस्तावेज़", library.note(id))
            library.unsave(id)
            val report = library.applyRetention(now + 100L * 86_400_000L)
            assertEquals(1, report.articlesDeleted)
            assertTrue(library.search("speech").isEmpty())
        }
        file.delete()
    }
}

@RunWith(AndroidJUnit4::class)
class LiveFeedsTest {
    @Test fun defaultPublisherFeedsParseOnARealNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = PhoneLibrary.refresher(context).refresh(force = true)
        report.sources.forEach { println("LIVE FEED ${it.key}: ${it.status} new=${it.newItems} error=${it.error}") }
        // Publishers can be briefly unavailable; the requirement is that real feeds parse, not that all five are up.
        assertTrue("No default feed could be fetched and parsed: ${report.sources}", report.sources.any { it.error == null && it.newItems > 0 })
    }
}

@RunWith(AndroidJUnit4::class)
class ScreensTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    // Section headers render in uppercase, so text matching ignores case.
    private fun waitFor(text: String, timeout: Long = 20_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }

    /** Lazy lists only compose visible rows, so scroll any scrollable container to the item first. */
    private fun reveal(text: String) {
        val matcher = hasText(text, substring = true, ignoreCase = true)
        if (rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()) return
        val scrollables = rule.onAllNodes(hasScrollAction())
        for (i in 0 until scrollables.fetchSemanticsNodes().size) {
            if (runCatching { scrollables[i].performScrollToNode(matcher) }.isSuccess) return
        }
    }

    private fun tap(text: String) {
        reveal(text)
        waitFor(text)
        val node = rule.onAllNodesWithText(text, ignoreCase = true)[0]
        runCatching { node.performScrollTo() }
        node.performClick(); rule.waitForIdle()
    }

    private fun tapIcon(description: String, substring: Boolean = false, timeout: Long = 20_000) {
        rule.waitUntil(timeout) { rule.onAllNodesWithContentDescription(description, substring = substring).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithContentDescription(description, substring = substring)[0].performClick(); rule.waitForIdle()
    }

    private fun hasIcon(description: String) = rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    /**
     * The test runner uninstalls the app afterwards (deleting its files), so screenshots are written to the
     * log as JPEG in base64 chunks; CI prints them for review.
     */
    private fun screenshot(name: String) {
        rule.waitForIdle()
        Thread.sleep(600) // let transitions settle
        val full = rule.onRoot().captureToImage().asAndroidBitmap()
        val bytes = java.io.ByteArrayOutputStream().also { full.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        val data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val chunks = data.chunked(3000)
        chunks.forEachIndexed { i, c -> android.util.Log.i("SCREENSHOT", "$name ${i + 1}/${chunks.size} $c"); Thread.sleep(8) } // avoid log rate limiting
    }

    /** Back to Settings, waiting for the transition to finish before the next tap. */
    private fun backToSettings() { tapIcon("Back"); waitFor("Model connections") }

    @Test fun walkThroughTheApp() = try { walk() } catch (e: Throwable) { screenshot("error-state"); throw e }

    private fun walk() {
        // First launch: onboarding.
        waitFor("Signal over noise.")
        screenshot("01-onboarding-welcome")
        tap("Get started")
        waitFor("What do you follow?")
        screenshot("02-onboarding-interests")
        tap("Continue")
        tap("Start reading")

        // Home with real feeds: refresh if onboarding did not already gather stories.
        rule.waitUntil(30_000) { hasIcon("More like this") || rule.onAllNodesWithText("Refresh now").fetchSemanticsNodes().isNotEmpty() }
        if (!hasIcon("More like this")) tap("Refresh now")
        try { rule.waitUntil(90_000) { hasIcon("More like this") } } catch (e: Throwable) { screenshot("error-home"); throw e }
        screenshot("03-home")

        // Briefing as stories, then one story in full.
        tapIcon("Briefing", substring = true)
        waitFor("Swipe up to read"); screenshot("04-briefing-story")
        tap("Swipe up to read")
        waitFor("Your note"); screenshot("05-article")
        tapIcon("Back"); tapIcon("Close")

        tapIcon("Explore"); waitFor("Search papers"); screenshot("06-explore")
        tapIcon("Ask"); waitFor("Ask your library"); screenshot("07-ask")
        tapIcon("Library"); waitFor("Highlights"); screenshot("08-library")
        tapIcon("Profile"); waitFor("Edit name"); screenshot("09-profile")
        tapIcon("Sources"); waitFor("active sources healthy"); screenshot("10-profile-sources")
        tapIcon("Topics"); tap("LLMs"); waitFor("you read"); screenshot("11-topic"); tapIcon("Back"); waitFor("Edit name")

        tapIcon("Settings and data"); waitFor("Model connections"); screenshot("12-settings")
        tap("Model connections"); waitFor("Privacy and cost"); screenshot("13-models"); backToSettings()
        tap("Backup and sync"); waitFor("How it works"); screenshot("14-sync"); backToSettings()
        tap("Appearance"); tap("Dark"); waitFor("Use wallpaper colors"); screenshot("15-appearance-dark"); backToSettings()
        tapIcon("Back"); waitFor("Edit name")
        tapIcon("Home"); rule.waitUntil(20_000) { hasIcon("More like this") }; screenshot("16-home-dark")
        tapIcon("Activity", substring = true); waitFor("Activity"); screenshot("17-activity")
    }
}

/** Sync encryption and merging on Android's own crypto providers (PBKDF2, AES-GCM), with an in-memory Drive. */
@RunWith(AndroidJUnit4::class)
class SyncOnAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class MemoryDrive : com.samacharam.core.sync.RemoteStore {
        val files = HashMap<String, ByteArray>()
        override fun list() = files.keys.toList()
        override fun read(name: String) = files[name]
        override fun write(name: String, data: ByteArray) { files[name] = data }
        override fun delete(name: String) { files.remove(name) }
    }

    private class MemoryKeys : com.samacharam.core.sync.SyncKeyStore {
        var key: ByteArray? = null
        override fun dataKey() = key
        override fun setDataKey(key: ByteArray) { this.key = key }
        override fun clear() { key = null }
    }

    @Test fun backupAndRestoreBetweenTwoLibraries() {
        val drive = MemoryDrive()
        val files = listOf("sync-a.db", "sync-b.db").map { File(context.cacheDir, it).also(File::delete) }
        BundledSqlDriver(files[0]).use { da ->
            BundledSqlDriver(files[1]).use { db ->
                val a = Library(da).also { it.open(); it.seedDefaults() }
                val b = Library(db).also { it.open(); it.seedDefaults() }
                val now = System.currentTimeMillis()
                val id = (a.upsertArticle(null, FeedItem("https://e.com/x", "Saved on A", "", now, "Excerpt.", ContentType.Blog, listOf("LLMs")), now) as UpsertResult.Inserted).id
                a.save(id, now, "note from A")
                com.samacharam.core.sync.SyncEngine(a, drive, MemoryKeys()) { now }.apply { createBackup("passphrase for test".toCharArray()); sync() }
                val engineB = com.samacharam.core.sync.SyncEngine(b, drive, MemoryKeys()) { now + 1000 }
                engineB.unlock("passphrase for test".toCharArray())
                engineB.sync()
                assertEquals("note from A", b.note(b.articleIdForUrl("https://e.com/x")!!))
            }
        }
        files.forEach(File::delete)
    }
}
