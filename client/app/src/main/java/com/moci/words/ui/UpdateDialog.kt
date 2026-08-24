package com.moci.words.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moci.words.api.AppReleaseInfo
import com.moci.words.update.DownloadProgress
import kotlin.math.roundToInt

@Composable
fun UpdateDialog(
    info: AppReleaseInfo,
    busy: Boolean,
    progress: DownloadProgress? = null,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
) {
    val sizeMb = (info.sizeBytes / 1048576.0).roundToInt()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("发现新版本") },
        text = {
            Column {
                Text("当前可更新到 v${info.versionName}（约 ${sizeMb} MB）。是否立即下载安装？")
                if (busy) {
                    Spacer(Modifier.height(14.dp))
                    val fraction = progress?.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Pine,
                            trackColor = Line,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Pine,
                            trackColor = Line,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        progress?.label() ?: "准备下载…",
                        fontSize = 13.sp,
                        color = InkSoft,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate, enabled = !busy) {
                val buttonText = when {
                    !busy -> "更新"
                    progress?.percent != null -> "下载中 ${progress.percent}%"
                    else -> "下载中…"
                }
                Text(buttonText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("稍后")
            }
        },
    )
}
