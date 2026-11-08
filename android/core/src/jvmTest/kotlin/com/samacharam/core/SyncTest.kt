package com.samacharam.core

import com.samacharam.core.db.Library
import com.samacharam.core.db.UpsertResult
import com.samacharam.core.sync.RemoteStore
import com.samacharam.core.sync.SyncEngine
import com.samacharam.core.sync.SyncException
import com.samacharam.core.sync.SyncKeyStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Two simulated phones sharing one in-memory "Drive" folder. */
class SyncTest {
    private val dir: File = Files.createTempDirectory("samacha-sync").toFile()
    private var now = 1_800_000_000_000L
    private val drive = MemoryDrive()
    private val pass = "correct horse battery".toCharArray()

    class MemoryDrive : RemoteStore {
        val files = LinkedHashMap<String, ByteArray>()
        override fun list() = files.keys.toList()
        override fun read(name: String) = files[name]
        override fun write(name: String, data: ByteArray) { files[name] = data }
        override fun delete(name: String) { files.remove(name) }
    }

    class MemoryKeys : SyncKeyStore {
        var key: ByteArray? = null
        override fun dataKey() = key
        override fun setDataKey(key: ByteArray) { this.key = key }
        override fun clear() { key = null }
    }

    inner class Phone(name: String) {
        val driver = JdbcDriver(File(dir, "$name.db"))
        val library = Library(driver).also { it.open(); it.seedDefaults() }
        val keys = MemoryKeys()
        val engine = SyncEngine(library, drive, keys) { now }
        fun sync() = engine.sync().also { now += 60_000 }
        fun saveArticle(n: Int, note: String = ""): Long {
            val id = (library.upsertArticle("hf", FeedItem("https://e.com/p$n", "Paper $n on retrieval", "Ada", now, "Excerpt $n.", ContentType.Paper, listOf("LLMs")), now) as UpsertResult.Inserted).id
            library.save(id, now, note)
            return id
        }
        fun savedNote(url: String) = library.articleIdForUrl(url)?.let { if (library.isSaved(it)) library.note(it) else null }
    }

    private val phones = mutableListOf<Phone>()
    private fun phone(name: String) = Phone(name).also { phones += it }

    @AfterTest fun cleanup() { phones.forEach { it.driver.close() }; dir.deleteRecursively() }

    @Test fun secondPhoneRestoresEverythingAndKeepsTheOwnersChoices() {
        val a = phone("a")
        // Worst case for ties: the joining phone's ID sorts higher, so only correct stamping keeps A's choices.
        a.library.setMeta("sync.deviceId", "0000000000000000")
        a.engine.createBackup(pass)
        a.library.setLayer("Hardware", Layer.Core)
        a.library.budgetMinutes = 25
        a.library.addSource("https://blog.example.com/feed.xml", "Example", ContentType.Blog, "Software")
        val id = a.saveArticle(1, "My note")
        a.library.addHighlight(id, "Excerpt 1 highlighted passage", now, "why it matters")
        a.library.addMission("Hardware week", setOf("Hardware"), "2099-01-01", now)
        a.sync()

        val b = phone("b")
        b.library.setMeta("sync.deviceId", "ffffffffffffffff")
        assertTrue(b.engine.backupExists())
        b.engine.unlock(pass)
        val report = b.sync()
        assertTrue(report.failed.isEmpty(), report.failed.toString())
        assertEquals(Layer.Core, b.library.profile().topics["Hardware"]) // not overwritten by b's first-run default
        assertEquals(25, b.library.budgetMinutes)
        assertTrue(b.library.sources().any { it.url == "https://blog.example.com/feed.xml" })
        assertEquals("My note", b.savedNote("https://e.com/p1"))
        assertEquals("why it matters", b.library.highlightsWithUrls().single().second.note)
        assertEquals("Hardware week", b.library.missions().single().title)
        assertEquals(5 + 1, b.library.sources().size) // defaults matched by URL, not duplicated
    }

    @Test fun passphraseRules() {
        val a = phone("a")
        assertFailsWith<SyncException> { a.engine.createBackup("short".toCharArray()) }
        a.engine.createBackup(pass)
        assertFailsWith<SyncException> { a.engine.createBackup(pass) }
        val b = phone("b")
        assertEquals("Wrong passphrase.", assertFailsWith<SyncException> { b.engine.unlock("not the passphrase".toCharArray()) }.message)
        assertFailsWith<SyncException> { b.sync() } // locked devices cannot sync
    }

    @Test fun deletionsPropagate() {
        val a = phone("a"); a.engine.createBackup(pass)
        val id = a.saveArticle(1, "note"); a.library.addSource("https://blog.example.com/feed.xml", "Example", ContentType.Blog, "Software")
        a.sync()
        val b = phone("b"); b.engine.unlock(pass); b.sync()
        assertEquals("note", b.savedNote("https://e.com/p1"))

        a.library.unsave(id)
        a.library.removeSource(a.library.sourceKeyForUrl("https://blog.example.com/feed.xml")!!)
        a.library.removeTopic("AI video")
        a.sync(); b.sync()
        assertEquals(null, b.savedNote("https://e.com/p1"))
        assertTrue(b.library.sources().none { it.url == "https://blog.example.com/feed.xml" })
        assertFalse("AI video" in b.library.profile().topics)
    }

    @Test fun concurrentNoteEditsKeepBothTexts() {
        val a = phone("a"); a.engine.createBackup(pass)
        a.saveArticle(1, "base"); a.sync()
        val b = phone("b"); b.engine.unlock(pass); b.sync()

        val idA = a.library.articleIdForUrl("https://e.com/p1")!!
        val idB = b.library.articleIdForUrl("https://e.com/p1")!!
        a.library.save(idA, now, "edited on A")
        b.library.save(idB, now, "edited on B")
        a.sync()
        val report = b.sync()
        assertEquals(1, report.conflicts)
        val merged = b.savedNote("https://e.com/p1")!!
        assertTrue("edited on A" in merged && "edited on B" in merged && SyncEngine.CONFLICT_MARK in merged, merged)

        a.sync()
        assertEquals(merged, a.savedNote("https://e.com/p1"))
        // Converged: further syncs change and upload nothing.
        assertEquals(0, b.sync().applied)
        val quiet = a.sync()
        assertEquals(0, quiet.applied)
        assertFalse(quiet.uploaded)
    }

    @Test fun joiningPhoneKeepsItsOwnItems() {
        val a = phone("a"); a.engine.createBackup(pass); a.saveArticle(1, "from A"); a.sync()
        val b = phone("b"); b.saveArticle(2, "made on B before joining")
        b.engine.unlock(pass); b.sync()
        assertEquals("from A", b.savedNote("https://e.com/p1"))
        a.sync()
        assertEquals("made on B before joining", a.savedNote("https://e.com/p2"))
    }

    @Test fun driveSeesOnlyCiphertextAndTamperingIsDetected() {
        val a = phone("a"); a.engine.createBackup(pass); a.saveArticle(1, "private thoughts about retrieval"); a.sync()
        drive.files.forEach { (name, bytes) ->
            val text = bytes.decodeToString()
            assertFalse("private thoughts" in text || "e.com/p1" in text, "plaintext leaked in $name")
        }
        val b = phone("b"); b.engine.unlock(pass)
        val name = drive.files.keys.single { it.startsWith("device-") }
        val original = drive.files.getValue(name)
        drive.files[name] = original.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<SyncException> { b.sync() }
        drive.files[name] = original
        drive.files["device-0000000000000000.srs"] = original // a copied file fails its name check (AAD)
        assertFailsWith<SyncException> { b.sync() }
        drive.files.remove("device-0000000000000000.srs")
        assertEquals("private thoughts about retrieval", b.sync().let { b.savedNote("https://e.com/p1") })
    }

    @Test fun unchangedStateDoesNotUpload() {
        val a = phone("a"); a.engine.createBackup(pass); a.saveArticle(1); assertTrue(a.sync().uploaded)
        assertFalse(a.sync().uploaded)
    }

    @Test fun disconnectCanRemoveTheDriveCopy() {
        val a = phone("a"); a.engine.createBackup(pass); a.saveArticle(1); a.sync()
        a.engine.disconnect(deleteRemote = true)
        assertTrue(drive.files.isEmpty())
        assertFalse(a.engine.unlocked)
        assertEquals("", a.savedNote("https://e.com/p1")) // local data stays
    }
}
