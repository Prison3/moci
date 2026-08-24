package com.moci.words.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun AvatarCropScreen(
    uri: Uri,
    onDismiss: () -> Unit,
    onConfirm: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cropBoxDp = 280.dp
    val cropSizePx = with(density) { cropBoxDp.toPx().roundToInt() }

    val source = remember(uri) { loadBitmapForCrop(context, uri) }
    if (source == null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Paper)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("无法读取照片", color = Ink)
            Spacer(Modifier.height(16.dp))
            MociButton("返回", onClick = onDismiss)
        }
        return
    }

  var scale by remember(source) {
        mutableFloatStateOf(initialCropScale(source, cropSizePx))
    }
    var offsetX by remember(source) { mutableFloatStateOf(0f) }
    var offsetY by remember(source) { mutableFloatStateOf(0f) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(
            initialCropScale(source, cropSizePx),
            initialCropScale(source, cropSizePx) * 4f,
        )
        offsetX += panChange.x
        offsetY += panChange.y
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "裁剪头像",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
        )
        Text(
            "拖动和缩放照片，圆圈内为头像区域",
            fontSize = 13.sp,
            color = InkSoft,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(cropBoxDp)
                    .clip(CircleShape)
                    .background(Paper2)
                    .transformable(state = transformState)
                    .drawWithContent {
                        drawContent()
                        drawCircle(
                            color = Color.White.copy(alpha = 0.35f),
                            radius = size.minDimension / 2f,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = source.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.None,
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        },
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MociButton(
                "取消",
                kind = BtnKind.Ghost,
                modifier = Modifier.weight(1f),
                onClick = onDismiss,
            )
            MociButton(
                "使用这张照片",
                modifier = Modifier.weight(1f),
                onClick = {
                    val cropped = cropSquareAvatar(
                        source = source,
                        cropViewSizePx = cropSizePx,
                        scale = scale,
                        offsetX = offsetX,
                        offsetY = offsetY,
                    )
                    val bytes = encodeAvatarJpeg(cropped)
                    if (cropped !== source) cropped.recycle()
                    onConfirm(bytes)
                },
            )
        }
    }
}
