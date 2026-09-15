package com.example.robocallguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object Notifications {
    private const val CHANNEL_ID = "blocked_calls"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Blocked calls", NotificationManager.IMPORTANCE_DEFAULT
            )
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun blockedCall(context: Context, number: String, reason: String) {
        ensureChannel(context)
        val hasPerm = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Blocked call")
            .setContentText("$number — $reason")
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(1001, notification)
    }

    fun smsAlert(context: Context, sender: String, flag: String) {
        ensureChannel(context)
        val hasPerm = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Possible spam SMS")
            .setContentText("$sender — $flag")
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(1002, notification)
    }
}
