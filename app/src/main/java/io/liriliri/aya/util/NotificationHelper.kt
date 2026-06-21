package io.liriliri.aya.util

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.liriliri.aya.AyaApplication
import io.liriliri.aya.R

object NotificationHelper {
    private var notifId = 1000

    fun show(context: Context, title: String, message: String, isError: Boolean = false) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, AyaApplication.CHANNEL_ID)
            .setSmallIcon(if (isError) android.R.drawable.ic_dialog_alert else android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        nm.notify(notifId++, notification)
    }

    fun showSuccess(context: Context, title: String, message: String) = show(context, "✅ $title", message, false)
    fun showError(context: Context, title: String, message: String) = show(context, "❌ $title", message, true)
}
