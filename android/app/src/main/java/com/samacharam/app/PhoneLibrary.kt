package com.samacharam.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.samacharam.core.ai.Location
import com.samacharam.core.app.BriefingSchedule
import com.samacharam.core.app.Feeds
import com.samacharam.core.db.Library
import com.samacharam.core.db.SqlDriver
import com.samacharam.core.db.SqlRow
import com.samacharam.core.feeds.FeedResponse
import com.samacharam.core.feeds.FeedTransport
import com.samacharam.core.feeds.RefreshReport
import com.samacharam.core.feeds.Refresher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [SqlDriver] over the SQLite bundled with androidx.sqlite, so FTS5 and contentless_delete
 * behave the same on every Android version. One connection, serialized by a lock.
 */
class BundledSqlDriver(file: File) : SqlDriver, AutoCloseable {
    private val connection: SQLiteConnection = BundledSQLiteDriver().open(file.absolutePath)
    private val lock = ReentrantLock()
    private var depth = 0

    private fun SQLiteStatement.bindAll(args: List<Any?>) {
        args.forEachIndexed { i, value ->
            val index = i + 1 // bind parameters are 1-based
            when (value) {
                null -> bindNull(index)
                is Long -> bindLong(index, value)
                is Int -> bindLong(index, value.toLong())
                is Boolean -> bindLong(index, if (value) 1L else 0L)
                is Double -> bindDouble(index, value)
                is String -> bindText(index, value)
                is ByteArray -> bindBlob(index, value)
                else -> throw IllegalArgumentException("Unsupported SQL argument ${value::class.simpleName}")
            }
        }
    }

    override fun execute(sql: String, args: List<Any?>) = lock.withLock {
        connection.prepare(sql).use { statement ->
            statement.bindAll(args)
            while (statement.step()) { }
        }
    }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> = lock.withLock {
        connection.prepare(sql).use { statement ->
            statement.bindAll(args)
            val row = object : SqlRow { // result columns are 0-based
                override fun long(index: Int): Long? = if (statement.isNull(index)) null else statement.getLong(index)
                override fun double(index: Int): Double? = if (statement.isNull(index)) null else statement.getDouble(index)
                override fun string(index: Int): String? = if (statement.isNull(index)) null else statement.getText(index)
                override fun bytes(index: Int): ByteArray? = if (statement.isNull(index)) null else statement.getBlob(index)
            }
            buildList { while (statement.step()) add(map(row)) }
        }
    }

    override fun <T> transaction(block: () -> T): T = lock.withLock {
        if (depth > 0) {
            depth++
            try { return block() } finally { depth-- }
        }
        execute("BEGIN IMMEDIATE")
        depth = 1
        try {
            val result = block()
            execute("COMMIT")
            result
        } catch (e: Throwable) {
            runCatching { execute("ROLLBACK") }
            throw e
        } finally {
            depth = 0
        }
    }

    override fun close() = lock.withLock { connection.close() }
}

/** The phone's library: opened once per process, seeded with default sources on first run. */
object PhoneLibrary {
    @Volatile private var instance: Library? = null

    fun get(context: Context): Library = instance ?: synchronized(this) {
        instance ?: Library(BundledSqlDriver(File(context.applicationContext.filesDir, "library.db"))).also {
            it.open()
            it.seedDefaults()
            instance = it
        }
    }

    fun refresher(context: Context): Refresher {
        val library = get(context)
        return Refresher(
            library = library,
            transport = OkHttpFeedTransport(),
            clock = System::currentTimeMillis,
            router = modelRouter(context.applicationContext, library::aiSettings, library.cloudUsage),
        )
    }

    const val BACKGROUND_WORK = "phone-library-refresh"

    /** Hourly refresh while connected and not low on battery. Android decides the exact time. */
    fun enableBackgroundRefresh(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
        val request = PeriodicWorkRequestBuilder<PhoneRefreshWorker>(1, TimeUnit.HOURS).setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(BACKGROUND_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun disableBackgroundRefresh(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(BACKGROUND_WORK)

    fun summary(report: RefreshReport): String =
        "Phone refresh: ${report.inserted} new, ${report.updated} updated, ${report.duplicates} duplicate copies skipped" +
            (if (report.modelTagged > 0) ", ${report.modelTagged} tagged by your model" else "") +
            (if (report.failed > 0) ". ${report.failed} source(s) unavailable; their earlier items are kept." else ".")
}

/** Public feeds only: addresses are checked (no private networks), redirects are handled by the core refresher. */
class OkHttpFeedTransport : FeedTransport {
    private val client = OkHttpClient.Builder()
        .dns(ValidatingDns(Location.Cloud))
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun get(url: String, headers: Map<String, String>): FeedResponse {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
        val response = try { client.newCall(request).execute() } catch (e: BlockedAddressException) {
            throw IllegalStateException("This feed points to a private network address and was blocked.")
        }
        response.use {
            val source = it.body?.source()
            val body = if (source == null) ByteArray(0) else {
                // Read at most one byte past the limit; the refresher rejects oversized feeds.
                source.request(Refresher.MAX_BYTES + 1L)
                source.buffer.readByteArray(minOf(source.buffer.size, Refresher.MAX_BYTES + 1L))
            }
            val names = it.headers.names()
            return FeedResponse(it.code, names.associate { name -> name.lowercase() to (it.header(name) ?: "") }, body)
        }
    }
}

class PhoneRefreshWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        PhoneLibrary.refresher(applicationContext).refresh()
        BriefingNotifier.notifyIfDue(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

/** Shows at most one briefing notification per local day, after the owner's chosen time. */
object BriefingNotifier {
    private const val CHANNEL = "briefing"

    fun notifyIfDue(context: Context) {
        val library = PhoneLibrary.get(context)
        val now = java.time.LocalDateTime.now()
        val today = now.toLocalDate().toString()
        if (!BriefingSchedule.due(library.briefingEnabled, library.briefingTime, today, now.hour * 60 + now.minute, library.briefingLastDay)) return
        val (queue, _) = Feeds.dailyBriefing(library, System.currentTimeMillis())
        library.briefingLastDay = today
        if (queue.articles.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Daily briefing", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Your briefing: ${queue.articles.size} items, about ${queue.minutes} min")
            .setContentText(queue.articles.first().title)
            .setContentIntent(open).setAutoCancel(true)
            .build()
        manager.notify(1, notification)
    }
}
