package com.moci.words.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.moci.words.MociApp
import com.moci.words.notify.MociNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 登录后保持 gRPC 双向流，以便后台也能收到词库 / 设置推送并弹出通知。
 */
class SyncForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        MociNotifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = MociNotifier.syncNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                MociNotifier.SYNC_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(MociNotifier.SYNC_NOTIFICATION_ID, notification)
        }

        val app = application as MociApp
        if (!app.api.hasSession) {
            stopSelf()
            return START_NOT_STICKY
        }
        app.api.startSync(scope)
        return START_STICKY
    }

    override fun onDestroy() {
        (application as MociApp).api.stopSync()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            val app = context.applicationContext
            ContextCompat.startForegroundService(app, Intent(app, SyncForegroundService::class.java))
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            app.stopService(Intent(app, SyncForegroundService::class.java))
        }
    }
}
