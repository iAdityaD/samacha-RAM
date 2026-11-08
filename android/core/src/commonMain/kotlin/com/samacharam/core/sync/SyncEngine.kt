package com.samacharam.core.sync

import com.samacharam.core.DAY_MS
import com.samacharam.core.db.Library
import com.samacharam.core.deflateRaw
import com.samacharam.core.formatDate
import com.samacharam.core.inflateRaw
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Files in the owner's storage (Drive appDataFolder). Implementations must only touch this app's private folder. */
interface RemoteStore {
    fun list(): List<String>
    fun read(name: String): ByteArray?
    fun write(name: String, data: ByteArray)
    fun delete(name: String)
}

/** One record. [stamp] orders edits (hybrid clock: never goes backwards on a device); ties break by [device]. */
data class SyncRecord(val key: String, val payload: JsonObject?, val stamp: Long, val device: String) {
    val deleted: Boolean get() = payload == null
    fun newerThan(o: SyncRecord) = stamp > o.stamp || (stamp == o.stamp && device > o.device)
}

data class SyncReport(val applied: Int, val conflicts: Int, val uploaded: Boolean, val devices: Int, val failed: List<String>)

/**
 * Device-local secrets for sync: the random data key, kept in the Android Keystore vault on the phone.
 */
interface SyncKeyStore {
    fun dataKey(): ByteArray?
    fun setDataKey(key: ByteArray)
    fun clear()
}

/**
 * Encrypted, compressed backup and sync.
 *
 * Every device writes one file holding the full merged state, so any single file restores everything and
 * merging is idempotent. A random 256-bit data key encrypts all files (AES-256-GCM, file name as AAD so files
 * cannot be swapped); `keys.json` stores that key wrapped with a key derived from the owner's passphrase.
 */
class SyncEngine(
    private val library: Library,
    private val remote: RemoteStore,
    private val keys: SyncKeyStore,
    private val clock: () -> Long,
) {
    companion object {
        const val KEY_FILE = "keys.json"
        const val MIN_PASSPHRASE = 10
        const val PBKDF2_ITERATIONS = 310_000
        private val MAGIC = "SRS1".encodeToByteArray()
        private const val TOMBSTONE_DAYS = 180
        const val CONFLICT_MARK = "--- Edited on another device"
    }

    private val deviceId: String
        get() = library.meta("sync.deviceId") ?: SyncCrypto.randomBytes(8).toHex().also { library.setMeta("sync.deviceId", it) }

    private val ownFile get() = "device-$deviceId.srs"

    // ---- passphrase and data key ----

    fun backupExists(): Boolean = KEY_FILE in remote.list()

    /** First device: creates the data key and stores it on Drive wrapped by the passphrase. */
    fun createBackup(passphrase: CharArray) {
        if (passphrase.size < MIN_PASSPHRASE) throw SyncException("Use a passphrase of at least $MIN_PASSPHRASE characters.")
        if (backupExists()) throw SyncException("A backup already exists. Enter its passphrase instead.")
        val dataKey = SyncCrypto.randomBytes(32)
        val salt = SyncCrypto.randomBytes(16)
        val wrapped = SyncCrypto.seal(SyncCrypto.deriveKey(passphrase, salt, PBKDF2_ITERATIONS), dataKey, KEY_FILE.encodeToByteArray())
        val file = buildJsonObject {
            put("version", 1); put("kdf", "PBKDF2-HMAC-SHA256"); put("iterations", PBKDF2_ITERATIONS)
            put("salt", salt.toHex()); put("wrappedKey", wrapped.toHex()); put("cipher", "AES-256-GCM")
        }
        remote.write(KEY_FILE, file.toString().encodeToByteArray())
        keys.setDataKey(dataKey)
    }

    /** Another device: unwraps the existing data key. A wrong passphrase fails authentication. */
    fun unlock(passphrase: CharArray) {
        val raw = remote.read(KEY_FILE) ?: throw SyncException("No backup was found in this Google account.")
        val o = runCatching { Json.parseToJsonElement(raw.decodeToString()) as JsonObject }.getOrNull() ?: throw SyncException("The backup key file is damaged.")
        val salt = (o["salt"] as? JsonPrimitive)?.content?.hexToBytes() ?: throw SyncException("The backup key file is damaged.")
        val iterations = (o["iterations"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 100_000..5_000_000 } ?: throw SyncException("The backup key file is damaged.")
        val wrapped = (o["wrappedKey"] as? JsonPrimitive)?.content?.hexToBytes() ?: throw SyncException("The backup key file is damaged.")
        val dataKey = try {
            SyncCrypto.open(SyncCrypto.deriveKey(passphrase, salt, iterations), wrapped, KEY_FILE.encodeToByteArray())
        } catch (e: SyncException) {
            throw SyncException("Wrong passphrase.")
        }
        keys.setDataKey(dataKey)
    }

    val unlocked: Boolean get() = keys.dataKey() != null

    // ---- file format ----

    fun encode(name: String, records: Collection<SyncRecord>, key: ByteArray): ByteArray {
        val json = buildJsonObject {
            put("version", 1); put("device", deviceId)
            putJsonArray("records") {
                records.sortedBy { it.key }.forEach { r ->
                    addJsonObject { put("k", r.key); put("t", r.stamp); put("d", r.device); r.payload?.let { put("p", it) } }
                }
            }
        }.toString().encodeToByteArray()
        return MAGIC + SyncCrypto.seal(key, deflateRaw(json), name.encodeToByteArray())
    }

    fun decode(name: String, data: ByteArray, key: ByteArray): List<SyncRecord> {
        if (data.size < MAGIC.size || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw SyncException("$name is not a samacha·RAM sync file.")
        val plain = inflateRaw(SyncCrypto.open(key, data.copyOfRange(MAGIC.size, data.size), name.encodeToByteArray()), 64 * 1024 * 1024)
        val root = Json.parseToJsonElement(plain.decodeToString()) as? JsonObject ?: throw SyncException("$name is damaged.")
        return (root["records"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            SyncRecord(
                (o["k"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                o["p"] as? JsonObject,
                (o["t"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
                (o["d"] as? JsonPrimitive)?.content ?: "",
            )
        }
    }

    // ---- local base state ----

    /** The merged state as of this device's last sync: the common ancestor used to detect local edits and conflicts. */
    private fun loadBase(): Map<String, SyncRecord> {
        val key = keys.dataKey() ?: return emptyMap()
        val stored = library.meta("sync.base")?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        return runCatching { decode("base", stored.hexToBytes(), key).associateBy { it.key } }.getOrDefault(emptyMap())
    }

    private fun saveBase(records: Collection<SyncRecord>, key: ByteArray) = library.setMeta("sync.base", encode("base", records, key).toHex())

    private fun tick(): Long {
        val last = library.meta("sync.lastStamp")?.toLongOrNull() ?: 0L
        return maxOf(clock(), last + 1).also { library.setMeta("sync.lastStamp", it.toString()) }
    }

    // ---- sync ----

    fun sync(): SyncReport {
        val dataKey = keys.dataKey() ?: throw SyncException("Enter your backup passphrase on this device first.")
        val me = deviceId
        val now = clock()

        // 1. Local edits since the last sync become records stamped now; untouched records keep their stamps.
        val base = loadBase()
        val local = SyncState.snapshot(library)
        // A device joining an existing backup has no base. Its records (often just first-run defaults) count as
        // the oldest, so the backup wins on overlaps; records only this device has are still kept and uploaded.
        val joining = base.isEmpty()
        val current = HashMap<String, SyncRecord>()
        val editedHere = HashSet<String>()
        for ((key, payload) in local) {
            val b = base[key]
            current[key] = if (b != null && !b.deleted && b.payload == payload) b else {
                editedHere += key
                SyncRecord(key, payload, if (joining) 0L else tick(), me)
            }
        }
        for ((key, b) in base) if (key !in local) {
            current[key] = if (b.deleted) b else { editedHere += key; SyncRecord(key, null, tick(), me) }
        }

        // 2. Merge every other device's file. A damaged or undecryptable file stops the sync before anything changes.
        val names = remote.list().filter { it.startsWith("device-") && it.endsWith(".srs") && it != ownFile }
        val merged = HashMap(current)
        var conflicts = 0
        for (name in names) {
            val records = decode(name, remote.read(name) ?: continue, dataKey)
            for (r in records) {
                val mine = merged[r.key]
                if (mine == null) { merged[r.key] = r; continue }
                if (mine == r) continue
                val combined = mergeNotes(r, mine, base[r.key], r.key in editedHere)
                if (combined != null) { merged[r.key] = combined; conflicts++ }
                else if (r.newerThan(mine)) merged[r.key] = r
            }
        }

        // 3. Apply what changed to the library, then record the merged state as the new base.
        val changedForLibrary = merged.keys.filter { key ->
            val m = merged.getValue(key)
            if (m.deleted) key in local else m.payload != local[key]
        }
        val failed = SyncState.apply(library, merged, changedForLibrary, now)

        val cutoff = now - TOMBSTONE_DAYS * DAY_MS
        val kept = merged.values.filter { !it.deleted || it.stamp >= cutoff }
        saveBase(kept, dataKey)

        // 4. Upload this device's view only when it changed.
        val digest = SyncCrypto.sha256(kept.sortedBy { it.key }.joinToString("\n") { "${it.key}|${it.stamp}|${it.device}|${it.payload}" }.encodeToByteArray()).toHex()
        val uploaded = digest != library.meta("sync.uploaded") || ownFile !in remote.list()
        if (uploaded) {
            remote.write(ownFile, encode(ownFile, kept, dataKey))
            library.setMeta("sync.uploaded", digest)
        }
        library.setMeta("sync.lastSync", now.toString())
        return SyncReport(changedForLibrary.size - failed.size, conflicts, uploaded, names.size + 1, failed)
    }

    /**
     * Notes are never silently lost: when both sides changed the same saved item's note since the last
     * common state, the newer record wins for other fields and the note keeps both texts, marked.
     */
    private fun mergeNotes(remote: SyncRecord, mine: SyncRecord, base: SyncRecord?, editedHere: Boolean): SyncRecord? {
        if (!remote.key.startsWith("saved:") || !editedHere || remote.deleted || mine.deleted) return null
        val baseNote = base?.payload?.let { (it["note"] as? JsonPrimitive)?.content } ?: ""
        val remoteNote = (remote.payload!!["note"] as? JsonPrimitive)?.content ?: ""
        val myNote = (mine.payload!!["note"] as? JsonPrimitive)?.content ?: ""
        if (remoteNote == baseNote || myNote == baseNote || remoteNote == myNote || CONFLICT_MARK in myNote && remoteNote in myNote) return null
        val (newer, older) = if (remote.newerThan(mine)) remote to mine else mine to remote
        val newerNote = if (newer === remote) remoteNote else myNote
        val olderNote = if (newer === remote) myNote else remoteNote
        val note = "$newerNote\n\n$CONFLICT_MARK (${formatDate(older.stamp)}) ---\n$olderNote"
        val payload = JsonObject(newer.payload!! + ("note" to JsonPrimitive(note)))
        return SyncRecord(remote.key, payload, maxOf(remote.stamp, mine.stamp) + 1, newer.device)
    }

    /** Stops syncing on this device. Local data stays; the Drive copy stays unless [deleteRemote]. */
    fun disconnect(deleteRemote: Boolean) {
        if (deleteRemote) remote.list().filter { it == KEY_FILE || (it.startsWith("device-") && it.endsWith(".srs")) }.forEach(remote::delete)
        keys.clear()
        listOf("sync.base", "sync.uploaded", "sync.lastSync").forEach { library.setMeta(it, "") }
    }
}
