package com.moci.words.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.moci.words.MainActivity
import com.moci.words.R

/** 系统通知：前台同步保活 + 词库 / 学习设置变更。 */
object MociNotifier {
    const val CHANNEL_SYNC = "moci_sync"
    const val CHANNEL_EVENTS = "moci_events"
    const val SYNC_NOTIFICATION_ID = 100
    const val EXTRA_KIND = "moci_notify_kind"
    const val KIND_WORDS = "words"
    const val KIND_SETTINGS = "settings"

    private const val WORDS_NOTIFICATION_ID = 1001
    private const val SETTINGS_NOTIFICATION_ID = 1002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                context.getString(R.string.notify_channel_sync),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notify_channel_sync_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                context.getString(R.string.notify_channel_events),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notify_channel_events_desc)
                enableVibration(true)
            },
        )
    }

    fun syncNotification(context: Context): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_stat_moci)
            .setContentTitle(context.getString(R.string.notify_sync_title))
            .setContentText(context.getString(R.string.notify_sync_text))
            .setContentIntent(openApp(context, null))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun notifyWordsUpdated(context: Context, action: String) {
        val (title, text) = when (action) {
            "created" -> R.string.notify_words_created_title to R.string.notify_words_created_text
            "updated" -> R.string.notify_words_updated_title to R.string.notify_words_updated_text
            "deleted" -> R.string.notify_words_deleted_title to R.string.notify_words_deleted_text
            else -> R.string.notify_words_updated_title to R.string.notify_words_generic_text
        }
        post(context, WORDS_NOTIFICATION_ID, context.getString(title), context.getString(text), KIND_WORDS)
    }

    fun notifySettingsUpdated(context: Context) {
        post(
            context,
            SETTINGS_NOTIFICATION_ID,
            context.getString(R.string.notify_settings_title),
            context.getString(R.string.notify_settings_text),
            KIND_SETTINGS,
        )
    }

    private fun post(context: Context, id: Int, title: String, text: String, kind: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_moci)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context, kind))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setColor(context.getColor(R.color.pine))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    private fun openApp(context: Context, kind: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (kind != null) putExtra(EXTRA_KIND, kind)
        }
        return PendingIntent.getActivity(
            context,
            kind?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
