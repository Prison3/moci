package com.moci.words.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class DownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long,
) {
    val fraction: Float? =
        if (totalBytes > 0) (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) else null

    val percent: Int? = fraction?.let { (it * 100).roundToInt().coerceIn(0, 100) }

    fun label(): String {
        if (totalBytes > 0 && percent != null) {
            return "${percent}%（${formatSize(bytesRead)} / ${formatSize(totalBytes)}）"
        }
        if (bytesRead > 0) {
            return "下载中… ${formatSize(bytesRead)}"
        }
        return "准备下载…"
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1048576.0
        return if (mb >= 1) "${mb.roundToInt()} MB" else "${(bytes / 1024).coerceAtLeast(1)} KB"
    }
}

object AppUpdater {
    private const val TAG = "AppUpdater"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    suspend fun downloadApk(
        context: Context,
        url: String,
        expectedBytes: Long = 0,
        onProgress: ((DownloadProgress) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        Log.i(TAG, "downloadApk url=$url expectedBytes=$expectedBytes")
        val response = client.newCall(Request.Builder().url(url).get().build()).execute()
        response.use { resp ->
            check(resp.isSuccessful) { "下载失败（${resp.code}）" }
            val body = resp.body ?: error("下载失败：空响应")
            val contentLength = body.contentLength()
            val totalBytes = when {
                contentLength > 0 -> contentLength
                expectedBytes > 0 -> expectedBytes
                else -> 0L
            }
            Log.i(
                TAG,
                "downloadApk response code=${resp.code} contentLength=$contentLength totalBytes=$totalBytes",
            )
            val out = File(context.cacheDir, "moci-update.apk")
            val buffer = ByteArray(8192)
            var bytesRead = 0L
            var lastReportAt = 0L

            suspend fun report(force: Boolean = false) {
                if (onProgress == null) return
                val now = System.currentTimeMillis()
                if (!force && now - lastReportAt < 200 && bytesRead < totalBytes) return
                lastReportAt = now
                val progress = DownloadProgress(bytesRead, totalBytes)
                withContext(Dispatchers.Main) { onProgress(progress) }
            }

            report(force = true)
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        bytesRead += read
                        report()
                    }
                }
            }
            report(force = true)
            Log.i(TAG, "downloadApk done path=${out.absolutePath} bytes=$bytesRead")
            out
        }
    }

    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }

    fun canInstallPackages(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }
}
