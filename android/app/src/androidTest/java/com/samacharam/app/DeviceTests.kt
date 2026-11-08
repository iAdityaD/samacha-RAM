package com.samacharam.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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

    private fun waitForText(text: String, atLeast: Int = 1) =
        rule.waitUntil(15_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().size >= atLeast }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun everyScreenOpensWithoutCrashing() {
        waitForText("Follow your curiosity.")
        screenshot("1-today")
        listOf(
            "Library" to "Keep the good things.",
            "Interests" to "Your interests, in layers.",
            "Sources" to "Where your news comes from.",
        ).forEachIndexed { i, (tab, heading) ->
            rule.onAllNodesWithText(tab)[0].performClick()
            waitForText(heading)
            screenshot("${i + 2}-${tab.lowercase()}")
        }
        rule.onAllNodesWithText("Settings")[0].performClick()
        waitForText("Daily briefing")
        screenshot("5-settings")
        rule.onAllNodesWithText("Model connections")[0].performClick()
        waitForText("Which model does each task")
        screenshot("6-models")
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
