package com.opencode.remote

import android.app.Activity
import android.app.Application
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.opencode.remote.data.download.ApkInstallReceiver
import dagger.hilt.android.HiltAndroidApp

/** P2：应用前后台追踪——前台时完成/确认通知免打扰（聊天的气泡已可见），后台才响铃震动。 */
object AppForegroundTracker {
    @Volatile
    var isForeground = false
        private set
    private var startedCount = 0

    fun onActivityStarted() {
        synchronized(this) {
            startedCount++
            isForeground = true
        }
    }

    fun onActivityStopped() {
        synchronized(this) {
            startedCount = maxOf(0, startedCount - 1)
            if (startedCount == 0) isForeground = false
        }
    }
}

@HiltAndroidApp
class OConnectorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 手机端应用内更新：下载完成自动调起安装
        try {
            ContextCompat.registerReceiver(
                this,
                ApkInstallReceiver(),
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            android.util.Log.w("OConnectorApp", "ApkInstallReceiver register failed", e)
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = AppForegroundTracker.onActivityStarted()
            override fun onActivityStopped(activity: Activity) = AppForegroundTracker.onActivityStopped()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OConnector Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps connection to server alive"
        }
        val completionChannel = NotificationChannel(
            CHANNEL_ID_COMPLETION,
            "AI Completion",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifies when an AI reply completes"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(completionChannel)
    }

    companion object {
        const val CHANNEL_ID = "sse_service_channel"
        const val CHANNEL_ID_COMPLETION = "ai_completion_channel"
    }
}
