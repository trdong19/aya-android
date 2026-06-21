package io.liriliri.aya

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AyaApplication : Application() {
    companion object {
        lateinit var instance: AyaApplication
            private set
        const val CHANNEL_ID = "aya_operations"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AYA 操作通知",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "安装、卸载、文件传输等操作通知"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}
