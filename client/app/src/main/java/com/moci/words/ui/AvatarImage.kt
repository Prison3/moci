package com.moci.words.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.max
import kotlin.math.roundToInt

const val AVATAR_IMG_PREFIX = "img:"

fun isPhotoAvatar(avatar: String): Boolean =
    avatar.startsWith(AVATAR_IMG_PREFIX)

fun decodeAvatarBitmap(avatar: String): Bitmap? {
    if (!isPhotoAvatar(avatar)) return null
    val raw = avatar.removePrefix(AVATAR_IMG_PREFIX)
    if (raw.isBlank()) return null
    return runCatching {
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

fun decodeAvatarImageBitmap(avatar: String): ImageBitmap? =
    decodeAvatarBitmap(avatar)?.asImageBitmap()

fun loadBitmapForCrop(context: Context, uri: Uri, maxDim: Int = 1024): Bitmap? {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, bounds)
            val sample = computeSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
        }
    }.getOrNull()
}

private fun computeSampleSize(width: Int, height: Int, maxDim: Int): Int {
    var sample = 1
    var w = width
    var h = height
    while (w > maxDim || h > maxDim) {
        sample *= 2
        w /= 2
        h /= 2
    }
    return sample
}

/** 按当前缩放与平移，从原图裁出正方形头像。 */
fun cropSquareAvatar(
    source: Bitmap,
    cropViewSizePx: Int,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    outputSize: Int = 256,
): Bitmap {
    val out = Bitmap.createBitmap(cropViewSizePx, cropViewSizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    val matrix = Matrix()
    val dx = (cropViewSizePx - source.width * scale) / 2f + offsetX
    val dy = (cropViewSizePx - source.height * scale) / 2f + offsetY
    matrix.postScale(scale, scale)
    matrix.postTranslate(dx, dy)
    canvas.drawBitmap(source, matrix, paint)
  return if (cropViewSizePx == outputSize) {
        out
    } else {
        Bitmap.createScaledBitmap(out, outputSize, outputSize, true).also {
            if (it !== out) out.recycle()
        }
    }
}

fun encodeAvatarJpeg(bitmap: Bitmap, maxBytes: Int = 120_000): ByteArray {
    var quality = 92
    var bytes = jpegBytes(bitmap, quality)
    while (bytes.size > maxBytes && quality > 50) {
        quality -= 8
        bytes = jpegBytes(bitmap, quality)
    }
    return bytes
}

private fun jpegBytes(bitmap: Bitmap, quality: Int): ByteArray {
    val stream = java.io.ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
    return stream.toByteArray()
}

fun avatarFromJpegBytes(bytes: ByteArray): String =
    AVATAR_IMG_PREFIX + Base64.encodeToString(bytes, Base64.NO_WRAP)

fun initialCropScale(bitmap: Bitmap, cropViewSizePx: Int): Float =
    max(
        cropViewSizePx.toFloat() / bitmap.width.toFloat(),
        cropViewSizePx.toFloat() / bitmap.height.toFloat(),
    )

fun cropViewSizePx(cropViewSizeDp: Float, density: Float): Int =
    (cropViewSizeDp * density).roundToInt().coerceAtLeast(1)
