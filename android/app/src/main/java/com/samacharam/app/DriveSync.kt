package com.samacharam.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.samacharam.core.ai.Location
import com.samacharam.core.db.Library
import com.samacharam.core.sync.RemoteStore
import com.samacharam.core.sync.SyncEngine
import com.samacharam.core.sync.SyncException
import com.samacharam.core.sync.SyncKeyStore
import com.samacharam.core.sync.SyncReport
import com.samacharam.core.sync.hexToBytes
import com.samacharam.core.sync.toHex
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Google sign-in limited to Drive's hidden app folder; the app cannot see the owner's other files. */
object GoogleDrive {
    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    fun request(): AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /**
     * Starts authorization from a screen. [needsUi] receives the consent screen to launch when Google asks;
     * otherwise [onToken] runs right away.
     */
    fun authorize(activity: Activity, needsUi: (AuthorizationResult) -> Unit, onToken: (String) -> Unit, onError: (String) -> Unit) {
        Identity.getAuthorizationClient(activity).authorize(request())
            .addOnSuccessListener { r -> if (r.hasResolution()) needsUi(r) else r.accessToken?.let(onToken) ?: onError("Google did not return access.") }
            .addOnFailureListener { onError(signInMessage(it)) }
    }

    fun tokenFromConsent(activity: Activity, data: Intent?): String? =
        runCatching { Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data).accessToken }.getOrNull()

    /** Background use: returns null when the owner must approve again in the app. */
    fun tokenSilently(context: Context): String? {
        val r = Tasks.await(Identity.getAuthorizationClient(context).authorize(request()), 30, TimeUnit.SECONDS)
        return if (r.hasResolution()) null else r.accessToken
    }

    fun signInMessage(e: Exception): String {
        val text = e.message.orEmpty()
        return when {
            "10:" in text || "DEVELOPER_ERROR" in text ->
                "Google sign-in is not set up for this app build. Check the Android client's package name and SHA-1 in Google Cloud."
            "12501" in text || "canceled" in text.lowercase() -> "Sign-in was cancelled."
            else -> "Google sign-in failed: ${text.take(160)}"
        }
    }
}

/** Drive v3 REST over OkHttp, restricted to the appDataFolder space. */
class DriveStore(private val token: String) : RemoteStore {
    private val client = OkHttpClient.Builder()
        .dns(ValidatingDns(Location.Cloud))
        .followSslRedirects(false)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private var ids: Map<String, List<String>>? = null

    private fun call(request: Request.Builder): ByteArray {
        client.newCall(request.header("Authorization", "Bearer $token").build()).execute().use { r ->
            val body = r.body?.bytes() ?: ByteArray(0)
            if (r.isSuccessful) return body
            val detail = runCatching { JSONObject(body.decodeToString()).getJSONObject("error").getString("message") }.getOrNull()
            throw SyncException(
                when (r.code) {
                    401 -> "Google sign-in expired. Open Settings and sync again."
                    403 -> "Google Drive refused access" + (detail?.let { ": ${it.take(160)}" } ?: ".")
                    else -> "Google Drive returned HTTP ${r.code}" + (detail?.let { ": ${it.take(160)}" } ?: ".")
                },
            )
        }
    }

    private fun files(): Map<String, List<String>> = ids ?: run {
        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("fields", "files(id,name,modifiedTime)")
            .addQueryParameter("orderBy", "modifiedTime desc")
            .addQueryParameter("pageSize", "1000").build()
        val list = JSONObject(call(Request.Builder().url(url).get()).decodeToString()).optJSONArray("files") ?: JSONArray()
        (0 until list.length()).map { list.getJSONObject(it) }
            .groupBy({ it.getString("name") }, { it.getString("id") }) // newest first within a name
            .also { ids = it }
    }

    override fun list(): List<String> = files().keys.toList()

    override fun read(name: String): ByteArray? {
        val id = files()[name]?.firstOrNull() ?: return null
        return call(Request.Builder().url("https://www.googleapis.com/drive/v3/files/$id?alt=media").get())
    }

    override fun write(name: String, data: ByteArray) {
        val existing = files()[name].orEmpty()
        val octet = "application/octet-stream".toMediaType()
        if (existing.isEmpty()) {
            val metadata = JSONObject().put("name", name).put("parents", JSONArray().put("appDataFolder")).toString()
            val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .addPart(data.toRequestBody(octet))
                .build()
            call(Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id").post(body))
        } else {
            call(Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files/${existing.first()}?uploadType=media").patch(data.toRequestBody(octet)))
            existing.drop(1).forEach { call(Request.Builder().url("https://www.googleapis.com/drive/v3/files/$it").delete()) }
        }
        ids = null
    }

    override fun delete(name: String) {
        files()[name].orEmpty().forEach { call(Request.Builder().url("https://www.googleapis.com/drive/v3/files/$it").delete()) }
        ids = null
    }
}

/** The sync data key, in the Android Keystore-backed vault. Never synced in plain form. */
class KeystoreSyncKeys(context: Context) : SyncKeyStore {
    private val vault = SecureStore(context)
    override fun dataKey(): ByteArray? = vault.get("sync-data-key")?.takeIf { it.isNotEmpty() }?.hexToBytes()
    override fun setDataKey(key: ByteArray) = vault.put("sync-data-key", key.toHex())
    override fun clear() = vault.remove("sync-data-key")
}

object PhoneSync {
    fun enabled(library: Library) = library.meta("storage.mode") == "drive"

    fun engine(context: Context, token: String) =
        SyncEngine(PhoneLibrary.get(context), DriveStore(token), KeystoreSyncKeys(context), System::currentTimeMillis)

    /** Records the outcome for the Settings screen. */
    fun syncNow(context: Context, token: String): SyncReport {
        val library = PhoneLibrary.get(context)
        return try {
            engine(context, token).sync().also { library.setMeta("sync.error", "") }
        } catch (e: Exception) {
            library.setMeta("sync.error", e.message ?: "Sync failed.")
            throw e
        }
    }

    /** Background sync after a refresh. Skipped (with a note) when Google needs the owner to approve again. */
    fun syncInBackground(context: Context) {
        val library = PhoneLibrary.get(context)
        if (!enabled(library)) return
        val token = runCatching { GoogleDrive.tokenSilently(context) }.getOrNull()
        if (token == null) { library.setMeta("sync.error", "Google needs you to approve access again. Open Settings → Sync now."); return }
        runCatching { syncNow(context, token) }
    }

    fun summary(r: SyncReport): String =
        "Synced with Google Drive: ${r.applied} change(s) applied" +
            (if (r.conflicts > 0) ", ${r.conflicts} note conflict(s) kept with both versions" else "") +
            (if (r.failed.isNotEmpty()) ", ${r.failed.size} item(s) could not be applied" else "") +
            (if (r.uploaded) ", backup updated." else ", backup already up to date.")
}
