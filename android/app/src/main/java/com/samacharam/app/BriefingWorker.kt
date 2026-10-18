package com.samacharam.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject

class BriefingWorker(context:Context,parameters:WorkerParameters):Worker(context,parameters) {
    override fun doWork():Result {
        return try {
            val api=ApiClient(applicationContext);val vault=SecureStore(applicationContext)
            val pass=vault.get("remembered-password")
            if(pass!=null)api.login(pass)
            val state=api.request("/api/state");OfflineStore(applicationContext).use { it.cache(state) }
            val digest=api.request("/api/digest",JSONObject())
            val manager=applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("briefing","Daily briefing",NotificationManager.IMPORTANCE_DEFAULT))
            if(Build.VERSION.SDK_INT<33 || applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) {
                val open=android.app.PendingIntent.getActivity(applicationContext,0,android.content.Intent(applicationContext,MainActivity::class.java),android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                manager.notify(1,android.app.Notification.Builder(applicationContext,"briefing").setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("Your samacha-RAM briefing").setContentText("${digest.optJSONArray("article_ids")?.length()?:0} items in your reading queue").setContentIntent(open).setAutoCancel(true).build())
            }
            Result.success()
        } catch(_:Exception) { Result.retry() }
    }
}
