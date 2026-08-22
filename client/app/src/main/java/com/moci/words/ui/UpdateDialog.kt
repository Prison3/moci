package com.moci.words.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.moci.words.api.AppReleaseInfo
import kotlin.math.roundToInt

@Composable
fun UpdateDialog(
    info: AppReleaseInfo,
    busy: Boolean,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
) {
    val sizeMb = (info.sizeBytes / 1048576.0).roundToInt()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("发现新版本") },
        text = {
            Text("当前可更新到 v${info.versionName}（约 ${sizeMb} MB）。是否立即下载安装？")
        },
        confirmButton = {
            TextButton(onClick = onUpdate, enabled = !busy) {
                Text(if (busy) "下载中…" else "更新")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("稍后")
            }
        },
    )
}
